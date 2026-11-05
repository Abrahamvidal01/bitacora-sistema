package com.sistema.bitacora.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReservaFormularioValidacionServiceTest {

    private final ReservaFormularioValidacionService servicio =
            new ReservaFormularioValidacionService();

    @Test
    void aceptaMotivoValido() {
        assertEquals(
                "Conferencia de computación",
                servicio.validarMotivo("Conferencia de computación")
        );
    }

    @Test
    void eliminaEspaciosAlPrincipioYAlFinal() {
        assertEquals(
                "Reunión académica",
                servicio.validarMotivo("  Reunión académica  ")
        );
    }

    @Test
    void rechazaMotivoNulo() {
        comprobarRechazo(null);
    }

    @Test
    void rechazaMotivoVacioOConSoloEspacios() {
        comprobarRechazo("");
        comprobarRechazo("   ");
    }

    @Test
    void aceptaExactamente255Caracteres() {
        String motivo = "a".repeat(255);

        assertEquals(motivo, servicio.validarMotivo(motivo));
    }

    @Test
    void rechazaMasDe255Caracteres() {
        comprobarRechazo("a".repeat(256));
    }

    private void comprobarRechazo(String motivo) {
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validarMotivo(motivo)
        );

        assertEquals(400, error.getStatusCode().value());
    }
}
