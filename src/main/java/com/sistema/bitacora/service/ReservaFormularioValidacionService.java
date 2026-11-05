package com.sistema.bitacora.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReservaFormularioValidacionService {

    public String validarMotivo(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Indica el motivo o nombre del evento"
            );
        }

        String normalizado = motivo.trim();

        if (normalizado.length() > 255) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El motivo admite hasta 255 caracteres"
            );
        }

        return normalizado;
    }
}
