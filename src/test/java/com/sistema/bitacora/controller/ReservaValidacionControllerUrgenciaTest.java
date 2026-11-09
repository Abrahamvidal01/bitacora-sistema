package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.ReservaCupoValidacionService;
import com.sistema.bitacora.service.ReservaFormularioValidacionService;
import com.sistema.bitacora.service.ReservaPdfValidacionService;
import com.sistema.bitacora.service.ReservaRecursosValidacionService;
import com.sistema.bitacora.service.ReservaValidacionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

// Pruebas unitarias de delegación y validación de parámetros.
// No inicia Spring ni utiliza la base de datos.
// No comprueba autenticación, CSRF, multipart HTTP real ni bloqueos concurrentes.
class ReservaValidacionControllerUrgenciaTest {

    private static final Long ESPACIO_ID = 2L;
    private static final Long DEPARTAMENTO_ID = 1L;
    private static final String CORREO = "titular@ejemplo.test";
    private static final String MOTIVO = "El titular no pudo ingresar al sistema";
    private static final LocalDate FECHA = LocalDate.of(2026, 11, 12);
    private static final LocalTime INICIO = LocalTime.of(9, 0);
    private static final LocalTime FIN = LocalTime.of(11, 0);

    private ReservaValidacionService validacion;
    private ReservaRecursosValidacionService recursos;
    private ReservaPdfValidacionService pdf;
    private ReservaCupoValidacionService cupos;
    private ReservaValidacionController controlador;

    @BeforeEach
    void preparar() {
        validacion = mock(ReservaValidacionService.class);
        recursos = mock(ReservaRecursosValidacionService.class);
        pdf = mock(ReservaPdfValidacionService.class);
        cupos = mock(ReservaCupoValidacionService.class);
        controlador = new ReservaValidacionController(
                validacion, recursos, new ReservaFormularioValidacionService(), pdf, cupos);
        when(recursos.validar(eq(ESPACIO_ID), anyList())).thenReturn(List.of());
        when(cupos.validar(anyLong(), any(LocalDate.class)))
                .thenReturn(new ReservaCupoValidacionService.ResultadoCupo(
                        DEPARTAMENTO_ID, "2026-11", 1, 0L, 1L));
    }

    @Test
    void urgenciaInternaDelegaElMotivoNormalizadoYNoUsaLaRutaNormal() {
        var parametros = parametrosUrgentes();
        parametros.set("motivoUrgencia", "  " + MOTIVO + "  ");
        prepararUrgencia("", false, ReservaValidacionService.ModalidadUrgencia.SOLO_INVENTARIO);

        var respuesta = llamar(parametros, archivosConOficio());
        var cuerpo = Objects.requireNonNull(respuesta.getBody());

        assertEquals(200, respuesta.getStatusCode().value());
        assertTrue(cuerpo.validacionInicialSuperada());
        assertTrue(cuerpo.mensaje().contains("Urgencia administrativa: mínimo 24 horas"));
        assertTrue(cuerpo.mensaje().contains("No se guardaron documentos"));
        verify(validacion).validarUrgenciaParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN, MOTIVO, "", false);
        verificarSinRutaNormal();
        verify(cupos).validar(DEPARTAMENTO_ID, FECHA);
    }

    @Test
    void externosYRequisicionSeDeleganComoUrgenciaConRevision() {
        var parametros = parametrosUrgentes();
        parametros.add("recursosExternos", "  Servicio externo de sonido  ");
        prepararUrgencia("Servicio externo de sonido", true,
                ReservaValidacionService.ModalidadUrgencia.CON_REQUISICION);

        var cuerpo = Objects.requireNonNull(llamar(parametros, archivosConDosPdf()).getBody());

        assertTrue(cuerpo.mensaje().contains("Urgencia administrativa: mínimo 72 horas"));
        assertTrue(cuerpo.mensaje().contains("requerirá revisión del supervisor"));
        assertTrue(cuerpo.mensaje().contains("PDF adjuntos comprobados: 2"));
        verify(validacion).validarUrgenciaParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN, MOTIVO,
                "Servicio externo de sonido", true);
        verificarSinRutaNormal();
    }

    @Test
    void externosSinRequisicionNoEvitanLaExigenciaDelDocumento() {
        var parametros = parametrosUrgentes();
        parametros.add("recursosExternos", "Mesas externas");
        prepararUrgencia("Mesas externas", false,
                ReservaValidacionService.ModalidadUrgencia.CON_REQUISICION);

        var error = assertThrows(ResponseStatusException.class,
                () -> llamar(parametros, archivosConOficio()));

        assertEquals(400, error.getStatusCode().value());
        assertTrue(Objects.requireNonNull(error.getReason()).contains("requisición"));
        verify(validacion).validarUrgenciaParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN, MOTIVO, "Mesas externas", false);
        verifyNoInteractions(cupos);
    }

    @Test
    void requisicionAdjuntaSinDescripcionExternaTambienExigeRevision() {
        prepararUrgencia("", true, ReservaValidacionService.ModalidadUrgencia.CON_REQUISICION);

        var cuerpo = Objects.requireNonNull(
                llamar(parametrosUrgentes(), archivosConDosPdf()).getBody());

        assertTrue(cuerpo.mensaje().contains("Urgencia administrativa: mínimo 72 horas"));
        assertTrue(cuerpo.mensaje().contains("Se adjuntó una requisición"));
        assertTrue(cuerpo.mensaje().contains("requerirá revisión del supervisor"));
        verify(validacion).validarUrgenciaParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN, MOTIVO, "", true);
    }

    @Test
    void elClienteNoPuedeElegirVeinticuatroHorasParaRecursosExternos() {
        var parametros = parametrosUrgentes();
        parametros.add("recursosExternos", "Servicio externo");
        parametros.add("horasMinimas", "24");
        parametros.add("modalidad", "SOLO_INVENTARIO");
        prepararUrgencia("Servicio externo", true,
                ReservaValidacionService.ModalidadUrgencia.CON_REQUISICION);

        var cuerpo = Objects.requireNonNull(llamar(parametros, archivosConDosPdf()).getBody());

        assertTrue(cuerpo.mensaje().contains("Urgencia administrativa: mínimo 72 horas"));
        verify(validacion).validarUrgenciaParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN, MOTIVO, "Servicio externo", true);
    }

    @Test
    void rechazaDosValoresDeLaOpcionUrgencia() {
        var parametros = parametrosUrgentes();
        parametros.add("esUrgencia", "false");
        rechazarAntesDeServicios(parametros);
    }

    @Test
    void rechazaOpcionUrgenciaDistintaDeTrueOFalse() {
        var parametros = parametrosUrgentes();
        parametros.set("esUrgencia", "on");
        rechazarAntesDeServicios(parametros);
    }

    @Test
    void rechazaDosMotivosDeUrgencia() {
        var parametros = parametrosUrgentes();
        parametros.add("motivoUrgencia", "Otro motivo");
        rechazarAntesDeServicios(parametros);
    }

    @Test
    void rechazaMotivoDeUrgenciaSiLaOpcionEstaDesmarcada() {
        var parametros = parametrosUrgentes();
        parametros.set("esUrgencia", "false");
        rechazarAntesDeServicios(parametros);
    }

    @Test
    void conservaElConflictoDeUrgenciaSinContinuarConRecursosNiPdfNiCupo() {
        when(validacion.validarUrgenciaParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN, MOTIVO, "", false))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "El horario está ocupado"));

        var error = assertThrows(ResponseStatusException.class,
                () -> llamar(parametrosUrgentes(), archivosConOficio()));
        var respuesta = controlador.manejarError(error);

        assertEquals(409, respuesta.getStatusCode().value());
        assertEquals("El horario está ocupado",
                Objects.requireNonNull(respuesta.getBody()).mensaje());
        verifyNoInteractions(recursos, pdf, cupos);
        verificarSinRutaNormal();
    }

    @Test
    void cupoDeUrgenciaUsaElDepartamentoYLaFechaValidadosPorElServidor() {
        LocalDate fechaServidor = LocalDate.of(2027, 1, 15);
        var parametros = parametrosUrgentes();
        parametros.add("departamentoId", "999");
        parametros.add("cupoMensual", "999");
        when(validacion.validarUrgenciaParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN, MOTIVO, "", false))
                .thenReturn(new ReservaValidacionService.ResultadoUrgenciaAdministrativa(
                        resultadoInicial(7L, fechaServidor), MOTIVO,
                        ReservaValidacionService.ModalidadUrgencia.SOLO_INVENTARIO));
        when(cupos.validar(7L, fechaServidor)).thenReturn(
                new ReservaCupoValidacionService.ResultadoCupo(7L, "2027-01", 1, 0L, 1L));

        var cuerpo = Objects.requireNonNull(llamar(parametros, archivosConOficio()).getBody());

        verify(cupos).validar(7L, fechaServidor);
        verify(cupos, never()).validar(eq(999L), any(LocalDate.class));
        verify(cupos, never()).validar(DEPARTAMENTO_ID, FECHA);
        assertEquals(fechaServidor.atTime(INICIO).toString(), cuerpo.inicio());
        assertTrue(cuerpo.mensaje().contains("Cupo mensual consultado para 2027-01"));
    }

    @Test
    void conservaElError413DelPdfTambienEnUrgencia() {
        prepararUrgencia("", false, ReservaValidacionService.ModalidadUrgencia.SOLO_INVENTARIO);
        var archivos = archivosConOficio();
        MultipartFile oficio = archivos.getFirst("oficioReservacion");
        when(pdf.validar(oficio)).thenThrow(new ResponseStatusException(
                HttpStatus.CONTENT_TOO_LARGE, "Cada documento puede pesar como máximo 3 MB"));

        var error = assertThrows(ResponseStatusException.class,
                () -> llamar(parametrosUrgentes(), archivos));

        assertEquals(413, error.getStatusCode().value());
        assertTrue(Objects.requireNonNull(error.getReason()).startsWith("Oficio de reservación:"));
        verifyNoInteractions(cupos);
    }

    @Test
    void urgenciaNoPermiteOmitirElOficio() {
        prepararUrgencia("", false, ReservaValidacionService.ModalidadUrgencia.SOLO_INVENTARIO);

        var error = assertThrows(ResponseStatusException.class,
                () -> llamar(parametrosUrgentes(), new LinkedMultiValueMap<>()));

        assertEquals(400, error.getStatusCode().value());
        assertTrue(Objects.requireNonNull(error.getReason()).contains("oficio"));
        verifyNoInteractions(cupos);
    }

    @Test
    void motivoUrgenteAusenteSeDelegaAlServicioQueLoExige() {
        var parametros = parametrosUrgentes();
        parametros.remove("motivoUrgencia");
        when(validacion.validarUrgenciaParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN, "", "", false))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Indica el motivo de la urgencia"));

        var error = assertThrows(ResponseStatusException.class,
                () -> llamar(parametros, archivosConOficio()));

        assertEquals(400, error.getStatusCode().value());
        verify(validacion).validarUrgenciaParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN, "", "", false);
        verifyNoInteractions(recursos, pdf, cupos);
    }

    @Test
    void elIndicadorDeRequisicionDelClienteNoSustituyeUnArchivoAdjunto() {
        var parametros = parametrosUrgentes();
        parametros.add("tieneRequisicion", "true");
        prepararUrgencia("", false, ReservaValidacionService.ModalidadUrgencia.SOLO_INVENTARIO);

        var cuerpo = Objects.requireNonNull(llamar(parametros, archivosConOficio()).getBody());

        verify(validacion).validarUrgenciaParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN, MOTIVO, "", false);
        assertTrue(cuerpo.mensaje().contains("Urgencia administrativa: mínimo 24 horas"));
        assertTrue(cuerpo.mensaje().contains("PDF adjuntos comprobados: 1"));
    }

    @Test
    void opcionFalseConservaLaRutaNormalDeSetentaYDosHoras() {
        var parametros = parametrosUrgentes();
        parametros.set("esUrgencia", "false");
        parametros.remove("motivoUrgencia");
        when(validacion.validarParaAdministrador(ESPACIO_ID, CORREO, FECHA, INICIO, FIN))
                .thenReturn(resultadoInicial(DEPARTAMENTO_ID, FECHA));

        var cuerpo = Objects.requireNonNull(llamar(parametros, archivosConOficio()).getBody());

        assertTrue(cuerpo.mensaje().contains("Modalidad normal: mínimo 72 horas"));
        verify(validacion).validarParaAdministrador(ESPACIO_ID, CORREO, FECHA, INICIO, FIN);
        verify(validacion, never()).validarUrgenciaParaAdministrador(
                anyLong(), anyString(), any(LocalDate.class), any(LocalTime.class),
                any(LocalTime.class), anyString(), anyString(), anyBoolean());
    }

    private void prepararUrgencia(String externos, boolean tieneRequisicion,
                                  ReservaValidacionService.ModalidadUrgencia modalidad) {
        when(validacion.validarUrgenciaParaAdministrador(
                ESPACIO_ID, CORREO, FECHA, INICIO, FIN, MOTIVO, externos, tieneRequisicion))
                .thenReturn(new ReservaValidacionService.ResultadoUrgenciaAdministrativa(
                        resultadoInicial(DEPARTAMENTO_ID, FECHA), MOTIVO, modalidad));
    }

    private ReservaValidacionService.ResultadoValidacionInicial resultadoInicial(
            Long departamentoId, LocalDate fecha) {
        return new ReservaValidacionService.ResultadoValidacionInicial(
                1L, 2L, departamentoId, 3L,
                fecha.atTime(INICIO), fecha.atTime(FIN), "America/Mexico_City");
    }

    private MultiValueMap<String, String> parametrosUrgentes() {
        var parametros = new LinkedMultiValueMap<String, String>();
        parametros.add("motivoEvento", "Reunión académica");
        parametros.add("esUrgencia", "true");
        parametros.add("motivoUrgencia", MOTIVO);
        return parametros;
    }

    private MockMultipartFile documentoSimulado(String nombre) {
        // Contenido ficticio: la validación real del PDF se prueba en su servicio.
        return new MockMultipartFile("documento", nombre, "application/pdf", new byte[]{1, 2, 3});
    }

    private MultiValueMap<String, MultipartFile> archivosConOficio() {
        var archivos = new LinkedMultiValueMap<String, MultipartFile>();
        archivos.add("oficioReservacion", documentoSimulado("oficio.pdf"));
        return archivos;
    }

    private MultiValueMap<String, MultipartFile> archivosConDosPdf() {
        var archivos = archivosConOficio();
        archivos.add("requisicion", documentoSimulado("requisicion.pdf"));
        return archivos;
    }

    private org.springframework.http.ResponseEntity<ReservaValidacionController.RespuestaValidacion>
    llamar(MultiValueMap<String, String> parametros,
           MultiValueMap<String, MultipartFile> archivos) {
        return controlador.comprobar(ESPACIO_ID, CORREO, FECHA.toString(),
                INICIO.toString(), FIN.toString(), parametros, archivos);
    }

    private void rechazarAntesDeServicios(MultiValueMap<String, String> parametros) {
        var error = assertThrows(ResponseStatusException.class,
                () -> llamar(parametros, archivosConOficio()));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(validacion, recursos, pdf, cupos);
    }

    private void verificarSinRutaNormal() {
        verify(validacion, never()).validarParaAdministrador(
                anyLong(), anyString(), any(LocalDate.class),
                any(LocalTime.class), any(LocalTime.class));
    }
}