package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.ReservaFormularioValidacionService;
import com.sistema.bitacora.service.ReservaRecursosValidacionService;
import com.sistema.bitacora.service.ReservaRecursosValidacionService.SolicitudRecurso;
import com.sistema.bitacora.service.ReservaValidacionService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/admin/reservas")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class ReservaValidacionController {

    private final ReservaValidacionService validacionService;
    private final ReservaRecursosValidacionService recursosService;
    private final ReservaFormularioValidacionService formularioService;

    public ReservaValidacionController(
            ReservaValidacionService validacionService,
            ReservaRecursosValidacionService recursosService,
            ReservaFormularioValidacionService formularioService) {

        this.validacionService = validacionService;
        this.recursosService = recursosService;
        this.formularioService = formularioService;
    }

    @PostMapping(
            value = "/validar-inicial",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<RespuestaValidacion> comprobar(
            @RequestParam(name = "espacioId") Long espacioId,
            @RequestParam(name = "correoSolicitante", defaultValue = "")
            String correoSolicitante,
            @RequestParam(name = "fechaEvento", defaultValue = "")
            String fechaEvento,
            @RequestParam(name = "horaInicio", defaultValue = "")
            String horaInicio,
            @RequestParam(name = "horaFin", defaultValue = "")
            String horaFin,
            @RequestParam MultiValueMap<String, String> parametros) {

        LocalDate fecha;
        LocalTime inicio;
        LocalTime fin;

        try {
            fecha = LocalDate.parse(fechaEvento.trim());
            inicio = LocalTime.parse(horaInicio.trim());
            fin = LocalTime.parse(horaFin.trim());
        } catch (DateTimeParseException ex) {
            throw error(
                    "Indica una fecha y horas de inicio y fin válidas");
        }

        comprobarMotivo(parametros);

        List<SolicitudRecurso> seleccion = leerRecursos(parametros);

        var resultado = validacionService.validarParaAdministrador(
                espacioId,
                correoSolicitante,
                fecha,
                inicio,
                fin
        );

        var recursos = recursosService.validar(espacioId, seleccion);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RespuestaValidacion(
                        true,
                        "Comprobación parcial superada: motivo, titular, anticipación, "
                                + "disponibilidad del espacio y recursos "
                                + "seleccionados válidos al consultar. "
                                + "Registros de inventario comprobados: "
                                + recursos.size() + ". "
                                + "No se creó una reserva, apartado ni folio. "
                                + "Falta comprobar documentos y cupos.",
                        resultado.inicio().toString(),
                        resultado.fin().toString(),
                        resultado.zonaHoraria()
                ));
    }

    private void comprobarMotivo(
            MultiValueMap<String, String> parametros) {

        List<String> valores = parametros.get("motivoEvento");

        if (valores == null || valores.size() != 1) {
            throw error(
                    "Indica un único motivo o nombre del evento");
        }

        formularioService.validarMotivo(valores.get(0));
    }

    private List<SolicitudRecurso> leerRecursos(
            MultiValueMap<String, String> parametros) {

        List<String> ids = parametros.getOrDefault(
                "articulosIds", List.of());

        List<SolicitudRecurso> seleccion = new ArrayList<>();
        Set<String> clavesCantidades = new HashSet<>();

        for (String valorId : ids) {
            Long id;

            try {
                id = Long.valueOf(valorId);
            } catch (NumberFormatException ex) {
                throw error(
                        "Los identificadores de artículos deben ser enteros");
            }

            String clave = "cantidades[" + id + "]";
            clavesCantidades.add(clave);

            List<String> valores = parametros.get(clave);

            if (valores == null || valores.size() != 1) {
                throw error(
                        "Indica una sola cantidad para cada artículo seleccionado");
            }

            Integer cantidad;

            try {
                cantidad = Integer.valueOf(valores.get(0));
            } catch (NumberFormatException ex) {
                throw error(
                        "Las cantidades deben ser enteros dentro del rango permitido");
            }

            seleccion.add(new SolicitudRecurso(id, cantidad));
        }

        for (String clave : parametros.keySet()) {
            if (clave.startsWith("cantidades")
                    && !clavesCantidades.contains(clave)) {
                throw error(
                        "Se recibió una cantidad sin un artículo seleccionado válido");
            }
        }

        return seleccion;
    }

    private ResponseStatusException error(String mensaje) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                mensaje
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<RespuestaError> manejarError(
            ResponseStatusException ex) {

        String mensaje = ex.getReason() == null
                ? "No se pudo completar la comprobación"
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
            String zonaHoraria) {
    }

    public record RespuestaError(String mensaje) {
    }
}