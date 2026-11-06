package com.sistema.bitacora.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReservaFormularioValidacionService {

    public String validarMotivo(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw error("Indica el motivo o nombre del evento");
        }

        String normalizado = motivo.trim();

        if (normalizado.length() > 255) {
            throw error("El motivo admite hasta 255 caracteres");
        }

        return normalizado;
    }

    /**
     * Regla para reservas sin autorización de entrega posterior.
     *
     * Los indicadores de documentos deben obtenerse en el servidor
     * a partir de los archivos recibidos, nunca de casillas del navegador.
     *
     * Este método comprueba obligatoriedad, no el contenido del PDF.
     */
    public void validarDocumentosRequeridos(
            String recursosExternos,
            boolean tieneOficio,
            boolean tieneRequisicion) {

        if (!tieneOficio) {
            throw error(
                    "Adjunta el oficio de reservación en formato PDF"
            );
        }

        boolean solicitaRecursosExternos =
                recursosExternos != null
                        && !recursosExternos.isBlank();

        if (solicitaRecursosExternos && !tieneRequisicion) {
            throw error(
                    "Adjunta la requisición en formato PDF porque "
                            + "solicitas recursos fuera del inventario "
                            + "de la escuela"
            );
        }
    }

    private ResponseStatusException error(String mensaje) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                mensaje
        );
    }
}