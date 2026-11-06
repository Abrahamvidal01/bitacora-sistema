package com.sistema.bitacora.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservaDocumentosRequeridosTest {

    private final ReservaFormularioValidacionService servicio =
            new ReservaFormularioValidacionService();

    @Test
    void aceptaSoloOficioCuandoNoSolicitaRecursosExternos() {
        assertDoesNotThrow(() ->
                servicio.validarDocumentosRequeridos(
                        "",
                        true,
                        false
                )
        );
    }

    @Test
    void aceptaSoloOficioCuandoElCampoExternoEsNulo() {
        assertDoesNotThrow(() ->
                servicio.validarDocumentosRequeridos(
                        null,
                        true,
                        false
                )
        );
    }

    @Test
    void noConsideraLosEspaciosEnBlancoComoUnaSolicitudExterna() {
        assertDoesNotThrow(() ->
                servicio.validarDocumentosRequeridos(
                        " \t\n ",
                        true,
                        false
                )
        );
    }

    @Test
    void rechazaLaFaltaDeOficioAunqueNoPidaRecursosExternos() {
        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validarDocumentosRequeridos(
                        "",
                        false,
                        false
                )
        );

        assertEquals(400, error.getStatusCode().value());
        assertTrue(Objects.requireNonNull(error.getReason())
                .contains("oficio"));
    }

    @Test
    void laRequisicionNoSustituyeAlOficio() {
        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validarDocumentosRequeridos(
                        "Servicio externo de sonido",
                        false,
                        true
                )
        );

        assertEquals(400, error.getStatusCode().value());
        assertTrue(Objects.requireNonNull(error.getReason())
                .contains("oficio"));
    }

    @Test
    void exigeRequisicionCuandoSolicitaRecursosExternos() {
        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validarDocumentosRequeridos(
                        "20 sillas fuera del inventario de la escuela",
                        true,
                        false
                )
        );

        assertEquals(400, error.getStatusCode().value());
        assertTrue(Objects.requireNonNull(error.getReason())
                .contains("requisición"));
    }

    @Test
    void aceptaRecursosExternosConAmbosDocumentos() {
        assertDoesNotThrow(() ->
                servicio.validarDocumentosRequeridos(
                        "Servicio externo de sonido",
                        true,
                        true
                )
        );
    }

    @Test
    void exigeRequisicionAunqueLosRecursosExternosSeanSinCosto() {
        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validarDocumentosRequeridos(
                        "Mesas prestadas sin costo, fuera del inventario",
                        true,
                        false
                )
        );

        assertEquals(400, error.getStatusCode().value());
        assertTrue(Objects.requireNonNull(error.getReason())
                .contains("requisición"));
    }
}