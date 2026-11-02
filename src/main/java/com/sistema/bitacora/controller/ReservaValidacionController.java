package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.ReservaValidacionService;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/admin/reservas")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class ReservaValidacionController {

    private final ReservaValidacionService validacionService;

    public ReservaValidacionController(ReservaValidacionService validacionService) {
        this.validacionService = validacionService;
    }

    @PostMapping(
            value = "/validar-inicial",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<RespuestaValidacion> comprobar(
            @RequestParam(name = "correoSolicitante", defaultValue = "")
            String correoSolicitante,
            @RequestParam(name = "fechaEvento", defaultValue = "")
            String fechaEvento,
            @RequestParam(name = "horaInicio", defaultValue = "")
            String horaInicio,
            @RequestParam(name = "horaFin", defaultValue = "")
            String horaFin) {

        LocalDate fecha;
        LocalTime inicio;
        LocalTime fin;

        try {
            fecha = LocalDate.parse(fechaEvento.trim());
            inicio = LocalTime.parse(horaInicio.trim());
            fin = LocalTime.parse(horaFin.trim());
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Indica una fecha y horas de inicio y fin válidas"
            );
        }

        var resultado = validacionService.validarParaAdministrador(
                correoSolicitante, fecha, inicio, fin
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RespuestaValidacion(
                        true,
                        "Comprobación inicial superada: titular y anticipación válidos. "
                                + "No se creó una reserva ni un folio. "
                                + "Falta comprobar disponibilidad, recursos, documentos y cupos.",
                        resultado.inicio().toString(),
                        resultado.fin().toString(),
                        resultado.zonaHoraria()
                ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<RespuestaError> manejarError(ResponseStatusException ex) {
        String mensaje = ex.getReason() == null
                ? "No se pudo completar la comprobación inicial"
                : ex.getReason();

        return ResponseEntity.status(ex.getStatusCode())
                .cacheControl(CacheControl.noStore())
                .body(new RespuestaError(mensaje));
    }

    public record RespuestaValidacion(
            boolean validacionInicialSuperada,
            String mensaje,
            String inicio,
            String fin,
            String zonaHoraria
    ) {
    }

    public record RespuestaError(String mensaje) {
    }
}
