package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.ReservaCupoValidacionService;
import com.sistema.bitacora.service.ReservaFormularioValidacionService;
import com.sistema.bitacora.service.ReservaPdfValidacionService;
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
import org.springframework.web.multipart.MultipartFile;
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
    private static final Set<String> DOCUMENTOS_PERMITIDOS =
            Set.of("oficioReservacion", "requisicion");
    private final ReservaValidacionService validacionService;
    private final ReservaRecursosValidacionService recursosService;
    private final ReservaFormularioValidacionService formularioService;
    private final ReservaPdfValidacionService pdfService;
    private final ReservaCupoValidacionService cupoService;

    public ReservaValidacionController(
            ReservaValidacionService validacionService,
            ReservaRecursosValidacionService recursosService,
            ReservaFormularioValidacionService formularioService,
            ReservaPdfValidacionService pdfService,
            ReservaCupoValidacionService cupoService) {
        this.validacionService = validacionService;
        this.recursosService = recursosService;
        this.formularioService = formularioService;
        this.pdfService = pdfService;
        this.cupoService = cupoService;
    }

    @PostMapping(value = "/validar-inicial",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RespuestaValidacion> comprobar(
            @RequestParam(name = "espacioId") Long espacioId,
            @RequestParam(name = "correoSolicitante", defaultValue = "") String correoSolicitante,
            @RequestParam(name = "fechaEvento", defaultValue = "") String fechaEvento,
            @RequestParam(name = "horaInicio", defaultValue = "") String horaInicio,
            @RequestParam(name = "horaFin", defaultValue = "") String horaFin,
            @RequestParam MultiValueMap<String, String> parametros,
            @RequestParam MultiValueMap<String, MultipartFile> archivos) {
        LocalDate fecha;
        LocalTime inicio;
        LocalTime fin;
        try {
            fecha = LocalDate.parse(fechaEvento.trim());
            inicio = LocalTime.parse(horaInicio.trim());
            fin = LocalTime.parse(horaFin.trim());
        } catch (DateTimeParseException ex) {
            throw error("Indica una fecha y horas de inicio y fin válidas");
        }
        comprobarMotivo(parametros);
        String recursosExternos = leerRecursosExternos(parametros);
        List<SolicitudRecurso> seleccion = leerRecursos(parametros);
        boolean esUrgencia = leerUrgencia(parametros);
        String motivoUrgencia = leerTextoOpcional(parametros, "motivoUrgencia");
        if (!esUrgencia && !motivoUrgencia.isBlank()) {
            throw error("Marca la atención por urgencia si indicas un motivo de urgencia");
        }

        // Se deriva de los adjuntos recibidos, no de un indicador del cliente.
        // Un adjunto vacío o inválido será rechazado al comprobar los PDF.
        boolean tieneRequisicion = archivos.containsKey("requisicion");
        ReservaValidacionService.ResultadoValidacionInicial resultado;
        String avisoModalidad = "Modalidad normal: mínimo 72 horas de anticipación. ";
        if (esUrgencia) {
            var urgencia = validacionService.validarUrgenciaParaAdministrador(
                    espacioId, correoSolicitante, fecha, inicio, fin,
                    motivoUrgencia, recursosExternos, tieneRequisicion);
            resultado = urgencia.validacion();
            avisoModalidad = "Urgencia administrativa: mínimo "
                    + urgencia.modalidad().horasMinimas()
                    + " horas de anticipación. ";
        } else {
            resultado = validacionService.validarParaAdministrador(
                    espacioId, correoSolicitante, fecha, inicio, fin);
        }
        var recursos = recursosService.validar(espacioId, seleccion);
        int documentosComprobados = comprobarDocumentos(archivos);
        formularioService.validarDocumentosRequeridos(
                recursosExternos,
                archivos.containsKey("oficioReservacion"),
                tieneRequisicion);
        var cupo = cupoService.validar(
                resultado.departamentoId(), resultado.inicio().toLocalDate());

        String avisoRevision;
        if (!recursosExternos.isBlank()) {
            avisoRevision = "Se declararon recursos fuera del inventario: "
                    + "la reserva requerirá revisión del supervisor. ";
        } else if (tieneRequisicion) {
            avisoRevision = "Se adjuntó una requisición: "
                    + "la reserva requerirá revisión del supervisor. ";
        } else {
            avisoRevision = "No se declararon recursos fuera del inventario. ";
        }
        String avisoCupo = "Cupo mensual consultado para " + cupo.mesEvento()
                + ": " + cupo.consumidos()
                + " consumido(s) de " + cupo.cupoMensual()
                + "; disponibles al consultar: " + cupo.disponibles() + ". ";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new RespuestaValidacion(
                        true,
                        "Comprobación parcial superada: motivo, titular, "
                                + "anticipación, disponibilidad del espacio, "
                                + "recursos seleccionados, documentación "
                                + "requerida y cupo mensual válidos al consultar. "
                                + avisoModalidad
                                + "Registros de inventario comprobados: " + recursos.size() + ". "
                                + "PDF adjuntos comprobados: " + documentosComprobados + ". "
                                + avisoRevision + avisoCupo
                                + "Esta comprobación no consume cupos, "
                                + "no garantiza disponibilidad al confirmar "
                                + "ni autoriza la entrega posterior de documentos. "
                                + "No se guardaron documentos ni se creó "
                                + "una reserva, apartado o folio.",
                        resultado.inicio().toString(), resultado.fin().toString(),
                        resultado.zonaHoraria()));
    }

    private boolean leerUrgencia(MultiValueMap<String, String> parametros) {
        List<String> valores = parametros.get("esUrgencia");
        if (valores == null) return false;
        if (valores.size() != 1) throw error("Indica una sola opción de urgencia");
        String valor = valores.get(0);
        if ("true".equals(valor)) return true;
        if ("false".equals(valor)) return false;
        throw error("La opción de urgencia debe ser true o false");
    }

    private String leerTextoOpcional(
            MultiValueMap<String, String> parametros, String nombre) {
        List<String> valores = parametros.get(nombre);
        if (valores == null) return "";
        if (valores.size() != 1 || valores.get(0) == null) {
            throw error("Indica un solo valor para " + nombre);
        }
        return valores.get(0).strip();
    }

    private String leerRecursosExternos(MultiValueMap<String, String> parametros) {
        List<String> valores = parametros.get("recursosExternos");
        if (valores == null) return "";
        if (valores.size() != 1 || valores.get(0) == null) {
            throw error("Indica una sola descripción de recursos externos");
        }
        return valores.get(0).strip();
    }

    private int comprobarDocumentos(MultiValueMap<String, MultipartFile> archivos) {
        int comprobados = 0;
        for (var entrada : archivos.entrySet()) {
            String nombreCampo = entrada.getKey();
            if (!DOCUMENTOS_PERMITIDOS.contains(nombreCampo)) {
                throw error("Se recibió un campo de documento no permitido");
            }
            List<MultipartFile> documentos = entrada.getValue();
            if (documentos == null || documentos.size() != 1 || documentos.get(0) == null) {
                throw error("Adjunta un solo PDF en cada campo de documento");
            }
            try {
                pdfService.validar(documentos.get(0));
            } catch (ResponseStatusException ex) {
                String etiqueta = nombreCampo.equals("oficioReservacion")
                        ? "Oficio de reservación" : "Requisición";
                String detalle = ex.getReason() == null
                        ? "No se pudo comprobar el PDF" : ex.getReason();
                throw new ResponseStatusException(ex.getStatusCode(), etiqueta + ": " + detalle);
            }
            comprobados++;
        }
        return comprobados;
    }

    private void comprobarMotivo(MultiValueMap<String, String> parametros) {
        List<String> valores = parametros.get("motivoEvento");
        if (valores == null || valores.size() != 1) {
            throw error("Indica un único motivo o nombre del evento");
        }
        formularioService.validarMotivo(valores.get(0));
    }

    private List<SolicitudRecurso> leerRecursos(MultiValueMap<String, String> parametros) {
        List<String> ids = parametros.getOrDefault("articulosIds", List.of());
        List<SolicitudRecurso> seleccion = new ArrayList<>();
        Set<String> clavesCantidades = new HashSet<>();
        for (String valorId : ids) {
            Long id;
            try {
                id = Long.valueOf(valorId);
            } catch (NumberFormatException ex) {
                throw error("Los identificadores de artículos deben ser enteros");
            }
            String clave = "cantidades[" + id + "]";
            clavesCantidades.add(clave);
            List<String> valores = parametros.get(clave);
            if (valores == null || valores.size() != 1) {
                throw error("Indica una sola cantidad para cada artículo seleccionado");
            }
            Integer cantidad;
            try {
                cantidad = Integer.valueOf(valores.get(0));
            } catch (NumberFormatException ex) {
                throw error("Las cantidades deben ser enteros dentro del rango permitido");
            }
            seleccion.add(new SolicitudRecurso(id, cantidad));
        }
        for (String clave : parametros.keySet()) {
            if (clave.startsWith("cantidades") && !clavesCantidades.contains(clave)) {
                throw error("Se recibió una cantidad sin un artículo seleccionado válido");
            }
        }
        return seleccion;
    }

    private ResponseStatusException error(String mensaje) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, mensaje);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<RespuestaError> manejarError(ResponseStatusException ex) {
        String mensaje = ex.getReason() == null
                ? "No se pudo completar la comprobación" : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode())
                .cacheControl(CacheControl.noStore()).body(new RespuestaError(mensaje));
    }

    public record RespuestaValidacion(boolean validacionInicialSuperada, String mensaje,
                                      String inicio, String fin, String zonaHoraria) {
    }
    public record RespuestaError(String mensaje) {
    }
}
