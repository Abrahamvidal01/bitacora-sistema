package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.ReservaRecursosValidacionService;
import com.sistema.bitacora.service.ReservaRecursosValidacionService.RecursoValidado;
import com.sistema.bitacora.service.ReservaRecursosValidacionService.SolicitudRecurso;
import com.sistema.bitacora.service.ReservaValidacionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Pruebas unitarias: no inicia Spring ni conecta a MySQL.
// No prueba autenticación, CSRF ni el enlace real de parámetros HTTP.
class ReservaValidacionControllerTest {

    private static final Long ESPACIO_ID = 2L;
    private static final String CORREO = "titular@ejemplo.test";
    private static final LocalDate FECHA = LocalDate.of(2026, 11, 10);
    private static final LocalTime INICIO = LocalTime.of(9, 0);
    private static final LocalTime FIN = LocalTime.of(11, 0);

    private ReservaValidacionService validacion;
    private ReservaRecursosValidacionService recursos;
    private ReservaValidacionController controlador;

    @BeforeEach
    void preparar() {
        validacion = mock(ReservaValidacionService.class);
        recursos = mock(ReservaRecursosValidacionService.class);

        controlador = new ReservaValidacionController(
                validacion, recursos);

        var resultado =
                new ReservaValidacionService.ResultadoValidacionInicial(
                        1L, 2L, 1L, 3L,
                        FECHA.atTime(INICIO),
                        FECHA.atTime(FIN),
                        "America/Mexico_City"
                );

        when(validacion.validarParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN))
                .thenReturn(resultado);

        when(recursos.validar(eq(ESPACIO_ID), anyList()))
                .thenReturn(List.of());
    }

    @Test
    void enviaCadaIdConSuCantidadCorrecta() {
        var parametros = seleccionSimple("1", "30");
        parametros.add("articulosIds", "6");
        parametros.add("cantidades[6]", "15");

        var seleccion = List.of(
                new SolicitudRecurso(1L, 30),
                new SolicitudRecurso(6L, 15)
        );

        when(recursos.validar(ESPACIO_ID, seleccion))
                .thenReturn(List.of(
                        new RecursoValidado(
                                1L, "Sillas", "BUENO", 30, 30),
                        new RecursoValidado(
                                6L, "Sillas", "REGULAR", 15, 15)
                ));

        var respuesta = comprobar(parametros);
        var cuerpo = Objects.requireNonNull(respuesta.getBody());

        assertEquals(200, respuesta.getStatusCode().value());
        assertTrue(cuerpo.validacionInicialSuperada());
        assertTrue(cuerpo.mensaje().contains(
                "Registros de inventario comprobados: 2"));

        verify(recursos).validar(ESPACIO_ID, seleccion);
    }

    @Test
    void permiteComprobarSinSeleccionarInventario() {
        var respuesta = comprobar(new LinkedMultiValueMap<>());

        assertEquals(200, respuesta.getStatusCode().value());
        verify(recursos).validar(ESPACIO_ID, List.of());
    }

    @Test
    void rechazaArticuloSinCantidad() {
        var parametros = new LinkedMultiValueMap<String, String>();
        parametros.add("articulosIds", "1");

        rechazaFormato(parametros);
    }

    @Test
    void rechazaCantidadSinArticuloSeleccionado() {
        var parametros = new LinkedMultiValueMap<String, String>();
        parametros.add("cantidades[1]", "3");

        rechazaFormato(parametros);
    }

    @Test
    void rechazaCantidadDecimalSinTruncarla() {
        rechazaFormato(seleccionSimple("1", "1.5"));
    }

    @Test
    void rechazaCantidadFueraDelRangoInteger() {
        rechazaFormato(seleccionSimple("1", "2147483648"));
    }

    @Test
    void rechazaDosValoresParaLaMismaCantidad() {
        var parametros = seleccionSimple("1", "10");
        parametros.add("cantidades[1]", "20");

        rechazaFormato(parametros);
    }

    @Test
    void rechazaIdentificadorDecimal() {
        rechazaFormato(seleccionSimple("1.5", "1"));
    }

    @Test
    void rechazaClaveDeCantidadMalFormada() {
        var parametros = seleccionSimple("1", "1");
        parametros.add("cantidades[abc]", "2");

        rechazaFormato(parametros);
    }

    @Test
    void conservaElErrorDelServicioDeRecursos() {
        when(recursos.validar(eq(ESPACIO_ID), anyList()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "El artículo está en estado MALO"
                ));

        var error = assertThrows(
                ResponseStatusException.class,
                () -> comprobar(seleccionSimple("1", "1"))
        );

        assertEquals(409, error.getStatusCode().value());

        var respuesta = controlador.manejarError(error);
        var cuerpo = Objects.requireNonNull(respuesta.getBody());

        assertEquals(409, respuesta.getStatusCode().value());
        assertEquals(
                "El artículo está en estado MALO",
                cuerpo.mensaje()
        );
    }

    @Test
    void noConsultaRecursosSiFallaLaValidacionInicial() {
        when(validacion.validarParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "El horario está ocupado"
                ));

        var error = assertThrows(
                ResponseStatusException.class,
                () -> comprobar(seleccionSimple("1", "1"))
        );

        assertEquals(409, error.getStatusCode().value());
        verifyNoInteractions(recursos);
    }

    @Test
    void rechazaFechaInvalidaAntesDeConsultarServicios() {
        var error = assertThrows(
                ResponseStatusException.class,
                () -> controlador.comprobar(
                        ESPACIO_ID,
                        CORREO,
                        "2026-99-99",
                        "09:00",
                        "11:00",
                        new LinkedMultiValueMap<>()
                )
        );

        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(validacion, recursos);
    }

    private MultiValueMap<String, String> seleccionSimple(
            String id,
            String cantidad) {

        var parametros = new LinkedMultiValueMap<String, String>();
        parametros.add("articulosIds", id);
        parametros.add("cantidades[" + id + "]", cantidad);

        return parametros;
    }

    private ResponseEntity<ReservaValidacionController.RespuestaValidacion>
    comprobar(MultiValueMap<String, String> parametros) {

        return controlador.comprobar(
                ESPACIO_ID,
                CORREO,
                FECHA.toString(),
                INICIO.toString(),
                FIN.toString(),
                parametros
        );
    }

    private void rechazaFormato(
            MultiValueMap<String, String> parametros) {

        var error = assertThrows(
                ResponseStatusException.class,
                () -> comprobar(parametros)
        );

        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(validacion, recursos);
    }
}
