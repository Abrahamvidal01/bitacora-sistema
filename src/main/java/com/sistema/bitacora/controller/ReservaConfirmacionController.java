package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.ReservaConfirmacionService;
import com.sistema.bitacora.service.ReservaRecursosValidacionService.SolicitudRecurso;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/admin/reservas")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class ReservaConfirmacionController {
    private static final Logger log = LoggerFactory.getLogger(ReservaConfirmacionController.class);
    private static final Set<String> CAMPOS = Set.of(
            "claveConfirmacion", "espacioId", "correoSolicitante", "fechaEvento",
            "horaInicio", "horaFin", "motivoEvento", "recursosExternos",
            "esUrgencia", "motivoUrgencia", "articulosIds", "_csrf");
    private static final Set<String> DOCUMENTOS = Set.of("oficioReservacion", "requisicion");
    private final ReservaConfirmacionService servicio;

    public ReservaConfirmacionController(ReservaConfirmacionService servicio) {
        this.servicio = servicio;
    }

    @GetMapping(value = "/confirmacion/estado", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EstadoConfirmacion> estado() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new EstadoConfirmacion(servicio.habilitada()));
    }

    @PostMapping(value = "/confirmar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReservaConfirmacionService.ResultadoConfirmacion> confirmar(
            @RequestParam MultiValueMap<String, String> parametros,
            @RequestParam MultiValueMap<String, MultipartFile> archivos) {
        if (parametros == null || archivos == null) {
            throw error("La solicitud de confirmación no es válida");
        }
        for (String clave : parametros.keySet()) {
            if (!CAMPOS.contains(clave) && !clave.startsWith("cantidades[")) {
                throw error("Se recibió un campo no permitido en la confirmación");
            }
        }
        String clave = obligatorio(parametros, "claveConfirmacion");
        try {
            if (!UUID.fromString(clave).toString().equals(clave)) {
                throw error("La clave de confirmación no es válida");
            }
        } catch (IllegalArgumentException ex) {
            throw error("La clave de confirmación no es válida");
        }
        Long espacioId = enteroLargo(obligatorio(parametros, "espacioId"));
        String correo = obligatorio(parametros, "correoSolicitante");
        if (correo.length() > 100) throw error("El correo admite hasta 100 caracteres");
        String motivo = obligatorio(parametros, "motivoEvento");
        if (motivo.length() > 255) throw error("El motivo admite hasta 255 caracteres");
        String externos = opcional(parametros, "recursosExternos");
        if (externos.getBytes(StandardCharsets.UTF_8).length > 65535) {
            throw error("La descripción de recursos externos es demasiado extensa");
        }
        String valorUrgencia = opcional(parametros, "esUrgencia");
        boolean urgencia;
        if (!parametros.containsKey("esUrgencia") || "false".equals(valorUrgencia)) urgencia = false;
        else if ("true".equals(valorUrgencia)) urgencia = true;
        else throw error("La opción de urgencia debe ser true o false");
        String motivoUrgencia = opcional(parametros, "motivoUrgencia");
        if (urgencia && motivoUrgencia.isBlank()) {
            throw error("Indica el motivo de la urgencia administrativa");
        }
        if (motivoUrgencia.length() > 255) throw error("El motivo de urgencia admite hasta 255 caracteres");
        if (!urgencia && !motivoUrgencia.isBlank()) {
            throw error("Marca la atención por urgencia si indicas un motivo de urgencia");
        }
        if (parametros.containsKey("_csrf")) opcional(parametros, "_csrf");
        LocalDate fecha;
        LocalTime inicio;
        LocalTime fin;
        try {
            String textoFecha = obligatorio(parametros, "fechaEvento");
            if (!textoFecha.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw error("Indica una fecha válida");
            }
            fecha = LocalDate.parse(textoFecha);
            if (fecha.getYear() < 1000) throw error("Indica una fecha válida");
            inicio = LocalTime.parse(obligatorio(parametros, "horaInicio"));
            fin = LocalTime.parse(obligatorio(parametros, "horaFin"));
        } catch (DateTimeParseException ex) {
            throw error("Indica una fecha y horas de inicio y fin válidas");
        }
        List<SolicitudRecurso> seleccion = leerRecursos(parametros);
        for (String nombre : archivos.keySet()) {
            if (!DOCUMENTOS.contains(nombre)) {
                throw error("Se recibió un campo de documento no permitido");
            }
        }
        MultipartFile oficio = documento(archivos, "oficioReservacion", true);
        MultipartFile requisicion = documento(archivos, "requisicion", false);
        var resultado = servicio.confirmar(new ReservaConfirmacionService.EntradaConfirmacion(
                clave, espacioId, correo, fecha, inicio, fin, motivo, externos,
                urgencia, motivoUrgencia, seleccion, oficio, requisicion));
        return ResponseEntity.status(resultado.repetida() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()).body(resultado);
    }

    private List<SolicitudRecurso> leerRecursos(MultiValueMap<String, String> parametros) {
        List<String> ids = parametros.getOrDefault("articulosIds", List.of());
        Set<Long> vistos = new HashSet<>();
        Set<String> cantidades = new HashSet<>();
        List<SolicitudRecurso> seleccion = new ArrayList<>();
        for (String texto : ids) {
            Long id = enteroLargo(texto);
            if (!vistos.add(id)) throw error("El mismo registro de inventario no puede repetirse");
            String nombre = "cantidades[" + id + "]";
            cantidades.add(nombre);
            String valor = obligatorio(parametros, nombre);
            int cantidad;
            try {
                cantidad = Integer.parseInt(valor);
                if (cantidad <= 0 || !Integer.toString(cantidad).equals(valor)) {
                    throw error("Las cantidades deben ser enteros positivos");
                }
            } catch (NumberFormatException ex) {
                throw error("Las cantidades deben ser enteros positivos dentro del rango permitido");
            }
            seleccion.add(new SolicitudRecurso(id, cantidad));
        }
        for (String nombre : parametros.keySet()) {
            if (nombre.startsWith("cantidades") && !cantidades.contains(nombre)) {
                throw error("Se recibió una cantidad sin un artículo seleccionado válido");
            }
        }
        return List.copyOf(seleccion);
    }

    private Long enteroLargo(String texto) {
        try {
            long valor = Long.parseLong(texto);
            if (valor <= 0 || !Long.toString(valor).equals(texto)) {
                throw error("Los identificadores deben ser enteros positivos");
            }
            return valor;
        } catch (NumberFormatException ex) {
            throw error("Los identificadores deben ser enteros positivos dentro del rango permitido");
        }
    }

    private String obligatorio(MultiValueMap<String, String> parametros, String nombre) {
        String valor = opcional(parametros, nombre);
        if (valor.isBlank()) throw error("Falta el campo obligatorio: " + nombre);
        return valor;
    }

    private String opcional(MultiValueMap<String, String> parametros, String nombre) {
        List<String> valores = parametros.get(nombre);
        if (valores == null) return "";
        if (valores.size() != 1 || valores.get(0) == null) {
            throw error("Indica un solo valor para " + nombre);
        }
        return valores.get(0).strip();
    }

    private MultipartFile documento(MultiValueMap<String, MultipartFile> archivos,
                                    String nombre, boolean obligatorio) {
        List<MultipartFile> lista = archivos.get(nombre);
        if (lista == null) {
            if (obligatorio) throw error("Adjunta el oficio de reservación en formato PDF");
            return null;
        }
        if (lista.size() != 1 || lista.get(0) == null || lista.get(0).isEmpty()) {
            throw error("Adjunta un solo PDF no vacío en el campo " + nombre);
        }
        return lista.get(0);
    }

    private ResponseStatusException error(String mensaje) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, mensaje);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<RespuestaError> manejarError(ResponseStatusException ex) {
        String mensaje = ex.getReason() == null ? "No se pudo confirmar la reserva" : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(new RespuestaError(mensaje));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public ResponseEntity<RespuestaError> manejarAcceso(RuntimeException ex) {
        boolean sinAutenticar = ex instanceof AuthenticationException;
        return ResponseEntity.status(sinAutenticar ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(new RespuestaError("Inicia sesión con una cuenta administradora activa para confirmar."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<RespuestaError> manejarIntegridad(DataIntegrityViolationException ex) {
        log.warn("Conflicto de integridad al confirmar una reserva", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(new RespuestaError("La confirmación entró en conflicto con otro registro. "
                        + "Comprueba el resultado antes de iniciar otra solicitud."));
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, TransactionTimedOutException.class})
    public ResponseEntity<RespuestaError> manejarEspera(RuntimeException ex) {
        log.warn("No se pudo obtener el bloqueo para confirmar una reserva", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(new RespuestaError("No se pudo completar la confirmación por concurrencia. "
                        + "Vuelve a comprobar disponibilidad antes de confirmar."));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<RespuestaError> manejarInesperado(RuntimeException ex) {
        log.error("Error inesperado al confirmar una reserva", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .cacheControl(CacheControl.noStore())
                .body(new RespuestaError("No se pudo determinar el resultado de la confirmación. "
                        + "Conserva los datos y reintenta con la misma clave; no inicies otra solicitud."));
    }

    public record EstadoConfirmacion(boolean habilitada) {
    }
    public record RespuestaError(String mensaje) {
    }
}