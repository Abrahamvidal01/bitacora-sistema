package com.sistema.bitacora.supervisorrevision;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/supervisor/api/urgencias")
@ConditionalOnProperty(name="bitacora.supervisor.revision-habilitada", havingValue="true")
@PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')")
public class UrgenciaRevisionController {
    private final UrgenciaRevisionService service;
    public UrgenciaRevisionController(UrgenciaRevisionService service) { this.service=service; }
    public record Failure(String mensaje,boolean resultadoIncierto) {}
    public record FreshCsrf(String headerName,String token) {}
    @GetMapping("/csrf") public ResponseEntity<FreshCsrf> csrf(CsrfToken currentCsrfToken) {
        service.checkAccess();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new FreshCsrf(currentCsrfToken.getHeaderName(),currentCsrfToken.getToken()));
    }
    @GetMapping public ResponseEntity<UrgenciaRevisionService.Month> list(@RequestParam("mes") String month) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(month)); }
    @GetMapping("/{id}/historial") public ResponseEntity<?> history(@PathVariable("id") long id) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(id)); }
    @GetMapping("/{id}/documentos/{tipo}") public ResponseEntity<byte[]> document(@PathVariable("id") long id,@PathVariable("tipo") String type) {
        byte[] bytes=service.document(id,type);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=\""+type+"-"+id+".pdf\"").body(bytes);
    }
    @PostMapping("/{id}/revision") public ResponseEntity<UrgenciaRevisionService.Result> apply(@PathVariable("id") long id,@RequestBody UrgenciaPolicy.Command command) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.apply(id,command));
    }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Failure> business(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).cacheControl(CacheControl.noStore()).body(new Failure(ex.getReason()==null?"Operación no aplicada":ex.getReason(),false));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class}) public ResponseEntity<Failure> invalid(Exception ex) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new Failure("Entrada no válida; operación no aplicada",false));
    }
    @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<Failure> forbidden(AccessDeniedException ex) {
        return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).body(new Failure("Acceso no autorizado",false));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<Failure> unknown(Exception ex) {
        // Commit/connection outcomes may be ambiguous; never disclose exception details or discard retry identity.
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(new Failure("No se pudo confirmar el resultado. Conserva la operación y reintenta la misma clave.",true));
    }
}