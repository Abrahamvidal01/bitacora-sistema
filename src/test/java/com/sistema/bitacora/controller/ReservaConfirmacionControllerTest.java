package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.ReservaConfirmacionService;
import com.sistema.bitacora.service.ReservaRecursosValidacionService.SolicitudRecurso;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Pruebas unitarias: llamadas directas al controlador con servicio simulado.
// No prueban la cadena de seguridad, CSRF, multipart HTTP real ni transacciones.
class ReservaConfirmacionControllerTest {
    private static final String CLAVE = "f9bb3c30-e277-4c1a-bdaa-b73f47c3c8e9";
    private ReservaConfirmacionService servicio;
    private ReservaConfirmacionController controlador;

    @BeforeEach
    void preparar() {
        servicio = mock(ReservaConfirmacionService.class);
        controlador = new ReservaConfirmacionController(servicio);
        when(servicio.confirmar(any(ReservaConfirmacionService.EntradaConfirmacion.class)))
                .thenReturn(resultado(false));
    }

    @Test
    void estadoExponeLaHabilitacionSinPermitirCache() {
        when(servicio.habilitada()).thenReturn(false);
        var deshabilitada = controlador.estado();
        assertFalse(Objects.requireNonNull(deshabilitada.getBody()).habilitada());
        assertEquals("no-store", deshabilitada.getHeaders().getCacheControl());
        when(servicio.habilitada()).thenReturn(true);
        assertTrue(Objects.requireNonNull(controlador.estado().getBody()).habilitada());
    }

    @Test
    void devuelve201YDelegaLosDatosNormalizadosYLosArchivos() {
        var parametros = parametros();
        parametros.set("correoSolicitante", "  titular@ejemplo.test  ");
        parametros.add("articulosIds", "7");
        parametros.add("cantidades[7]", "5");
        var archivos = archivos();
        var respuesta = controlador.confirmar(parametros, archivos);
        assertEquals(201, respuesta.getStatusCode().value());
        assertEquals("no-store", respuesta.getHeaders().getCacheControl());
        assertFalse(Objects.requireNonNull(respuesta.getBody()).repetida());
        var entrada = ArgumentCaptor.forClass(ReservaConfirmacionService.EntradaConfirmacion.class);
        verify(servicio).confirmar(entrada.capture());
        var valor = entrada.getValue();
        assertEquals(CLAVE, valor.claveConfirmacion());
        assertEquals(2L, valor.espacioId());
        assertEquals("titular@ejemplo.test", valor.correoSolicitante());
        assertEquals(LocalDate.of(2026, 11, 12), valor.fechaEvento());
        assertEquals(LocalTime.of(9, 0), valor.horaInicio());
        assertEquals(List.of(new SolicitudRecurso(7L, 5)), valor.recursos());
        assertSame(archivos.getFirst("oficioReservacion"), valor.oficio());
        assertNull(valor.requisicion());
        assertFalse(valor.esUrgencia());
    }

    @Test
    void devuelve200AlRepetirUnaConfirmacionYaRegistrada() {
        when(servicio.confirmar(any(ReservaConfirmacionService.EntradaConfirmacion.class)))
                .thenReturn(resultado(true));
        var respuesta = controlador.confirmar(parametros(), archivos());
        assertEquals(200, respuesta.getStatusCode().value());
        assertTrue(Objects.requireNonNull(respuesta.getBody()).repetida());
        assertEquals("RES-2026-000001", respuesta.getBody().folio());
    }

    @Test
    void rechazaCamposBasicosDuplicados() {
        var parametros = parametros();
        parametros.add("espacioId", "3");
        rechazar(parametros, archivos());
    }

    @Test
    void rechazaUnaOpcionUrgenciaQueNoSeaTrueOFalse() {
        var parametros = parametros();
        parametros.set("esUrgencia", "on");
        rechazar(parametros, archivos());
    }

    @Test
    void rechazaUnDepartamentoOLimiteEnviadoPorElCliente() {
        var parametros = parametros();
        parametros.add("departamentoId", "999");
        parametros.add("cupoMensual", "999");
        rechazar(parametros, archivos());
    }

    @Test
    void rechazaLaFaltaDeOficioAntesDeDelegar() {
        rechazar(parametros(), new LinkedMultiValueMap<>());
    }

    @Test
    void rechazaDosArchivosEnElMismoCampo() {
        var archivos = archivos();
        archivos.add("oficioReservacion", documento("otro.pdf"));
        rechazar(parametros(), archivos);
    }

    @Test
    void rechazaUnCampoDeDocumentoNoPermitido() {
        var archivos = archivos();
        archivos.add("adjuntoNoPermitido", documento("otro.pdf"));
        rechazar(parametros(), archivos);
    }

    @Test
    void rechazaUnMismoRegistroDeInventarioRepetido() {
        var parametros = parametros();
        parametros.add("articulosIds", "7");
        parametros.add("articulosIds", "7");
        parametros.add("cantidades[7]", "5");
        rechazar(parametros, archivos());
    }

    @Test
    void rechazaUnaClaveQueNoSeaUuidCanonico() {
        var parametros = parametros();
        parametros.set("claveConfirmacion", "1-1-1-1-1");
        rechazar(parametros, archivos());
    }

    @Test
    void conservaElEstado503CuandoElServicioEstaDeshabilitado() {
        when(servicio.confirmar(any(ReservaConfirmacionService.EntradaConfirmacion.class)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "La confirmación todavía no está habilitada"));
        var error = assertThrows(ResponseStatusException.class,
                () -> controlador.confirmar(parametros(), archivos()));
        var respuesta = controlador.manejarError(error);
        assertEquals(503, respuesta.getStatusCode().value());
        assertEquals("La confirmación todavía no está habilitada",
                Objects.requireNonNull(respuesta.getBody()).mensaje());
        assertEquals("no-store", respuesta.getHeaders().getCacheControl());
    }

    @Test
    void unConflictoDeIntegridadNoExponeElSqlEnLaRespuesta() {
        var respuesta = controlador.manejarIntegridad(
                new DataIntegrityViolationException("SQL secreto de prueba y datos internos"));
        assertEquals(409, respuesta.getStatusCode().value());
        String mensaje = Objects.requireNonNull(respuesta.getBody()).mensaje();
        assertFalse(mensaje.contains("SQL secreto"));
        assertEquals("no-store", respuesta.getHeaders().getCacheControl());
    }

    @Test
    void urgenciaExigeMotivoAntesDeDelegar() {
        var parametros = parametros();
        parametros.set("esUrgencia", "true");
        parametros.add("motivoUrgencia", "   ");
        rechazar(parametros, archivos());
    }

    private ReservaConfirmacionService.ResultadoConfirmacion resultado(boolean repetida) {
        return new ReservaConfirmacionService.ResultadoConfirmacion(
                101L, "RES-2026-000001", "APROBADA", repetida, "Reserva registrada");
    }

    private MultiValueMap<String, String> parametros() {
        var parametros = new LinkedMultiValueMap<String, String>();
        parametros.add("claveConfirmacion", CLAVE);
        parametros.add("espacioId", "2");
        parametros.add("correoSolicitante", "titular@ejemplo.test");
        parametros.add("fechaEvento", "2026-11-12");
        parametros.add("horaInicio", "09:00");
        parametros.add("horaFin", "11:00");
        parametros.add("motivoEvento", "Reunión académica");
        parametros.add("recursosExternos", "");
        parametros.add("esUrgencia", "false");
        return parametros;
    }

    private MockMultipartFile documento(String nombre) {
        return new MockMultipartFile("documento", nombre, "application/pdf", new byte[]{1, 2, 3});
    }

    private MultiValueMap<String, MultipartFile> archivos() {
        var archivos = new LinkedMultiValueMap<String, MultipartFile>();
        archivos.add("oficioReservacion", documento("oficio.pdf"));
        return archivos;
    }

    private void rechazar(MultiValueMap<String, String> parametros,
                          MultiValueMap<String, MultipartFile> archivos) {
        var error = assertThrows(ResponseStatusException.class,
                () -> controlador.confirmar(parametros, archivos));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(servicio);
    }
}