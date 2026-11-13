package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.DashboardAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/admin/dashboard/datos")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class DashboardAdminController {
    private static final Logger log = LoggerFactory.getLogger(DashboardAdminController.class);
    private final DashboardAdminService servicio;
    public DashboardAdminController(DashboardAdminService servicio) { this.servicio = servicio; }
    @GetMapping("/resumen")
    public ResponseEntity<?> resumen(@RequestParam(name = "mes", required = false) String mes) {
        return datos(servicio.resumen(mes));
    }
    @GetMapping("/espacios")
    public ResponseEntity<?> espacios() { return datos(servicio.espacios()); }
    @GetMapping("/historial")
    public ResponseEntity<?> historial(@RequestParam(name = "mes", required = false) String mes,
            @RequestParam(name = "pagina", defaultValue = "0") int pagina) {
        return datos(servicio.historial(mes, pagina));
    }
    @GetMapping("/agenda")
    public ResponseEntity<?> agenda(@RequestParam(name = "fecha", required = false) String fecha,
            @RequestParam(name = "espacioId", required = false) Long espacioId) {
        return datos(servicio.agenda(fecha, espacioId));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> controlado(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("mensaje", ex.getReason() == null ? "No se pudo consultar" : ex.getReason()));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> parametro(MethodArgumentTypeMismatchException ex) {
        return fallo(HttpStatus.BAD_REQUEST, "Los parámetros de consulta no son válidos");
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> denegado(AccessDeniedException ex) {
        return fallo(HttpStatus.FORBIDDEN, "No tienes permiso para consultar este panel");
    }
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<?> sesion(AuthenticationException ex) {
        return fallo(HttpStatus.UNAUTHORIZED, "Vuelve a iniciar sesión");
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> inesperado(RuntimeException ex) {
        log.error("No se pudieron consultar los datos del dashboard administrativo", ex);
        return fallo(HttpStatus.INTERNAL_SERVER_ERROR,
                "No se pudieron consultar los datos. No se mostrará información de ejemplo");
    }
    private static ResponseEntity<?> datos(Object datos) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(datos);
    }
    private static ResponseEntity<?> fallo(HttpStatus estado, String mensaje) {
        return ResponseEntity.status(estado).cacheControl(CacheControl.noStore())
                .body(Map.of("mensaje", mensaje));
    }
}
