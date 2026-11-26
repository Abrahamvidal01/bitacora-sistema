package com.sistema.bitacora.supervisorcuentas;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Static proposal: uses the existing session security chain; no account writes. */
@RestController
public class GestionCuentasCatalogoController {
    private static final String ACTOR_SQL = """
            SELECT COUNT(*) FROM usuarios u
            WHERE u.activo = 1 AND LOWER(TRIM(u.correo_institucional)) = ?
              AND EXISTS (SELECT 1 FROM usuario_roles ur JOIN roles r ON r.id = ur.rol_id
                WHERE ur.usuario_id = u.id AND BINARY r.nombre_rol = BINARY ?)
              AND EXISTS (SELECT 1 FROM usuario_roles ur JOIN roles r ON r.id = ur.rol_id
                WHERE ur.usuario_id = u.id AND BINARY r.nombre_rol = BINARY ?)
            """;
    private static final String DEPARTAMENTOS_SQL = """
            SELECT id, nombre_departamento FROM departamentos
            WHERE activo = 1 ORDER BY nombre_departamento, id LIMIT 2001
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate lectura;

    public GestionCuentasCatalogoController(JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.lectura = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        lectura.setReadOnly(true);
        lectura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        lectura.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @GetMapping(value = "/supervisor/api/cuentas/departamentos",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMINISTRADOR') and hasRole('SUPERVISOR')")
    public ResponseEntity<List<DepartamentoOpcion>> departamentos(
            HttpServletRequest request, Authentication authentication) {
        exigirSesion(request, authentication);
        try {
            if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) {
                throw rechazo(HttpStatus.BAD_REQUEST);
            }
        } catch (IllegalArgumentException malformedParameters) {
            throw rechazo(HttpStatus.BAD_REQUEST);
        }
        String correo = authentication.getName().trim().toLowerCase(Locale.ROOT);
        List<DepartamentoOpcion> opciones = lectura.execute(status -> {
            Long count = jdbc.queryForObject(ACTOR_SQL, Long.class, correo,
                    "ADMINISTRADOR", "SUPERVISOR");
            if (count == null || count != 1L) {
                throw rechazo(HttpStatus.FORBIDDEN);
            }
            List<DepartamentoOpcion> rows = jdbc.query(DEPARTAMENTOS_SQL,
                    (rs, rowNum) -> new DepartamentoOpcion(rs.getObject("id", Long.class),
                            rs.getString("nombre_departamento")));
            if (rows == null || rows.size() > 2000) {
                throw rechazo(HttpStatus.CONFLICT);
            }
            for (DepartamentoOpcion row : rows) {
                if (row == null || row.id() == null || row.id() <= 0 || row.nombre() == null
                        || row.nombre().isBlank() || row.nombre().length() > 100) {
                    throw rechazo(HttpStatus.CONFLICT);
                }
            }
            return List.copyOf(rows);
        });
        if (opciones == null) {
            throw rechazo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(opciones);
    }

    private static void exigirSesion(HttpServletRequest request, Authentication auth) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth != current || !autorizado(auth)) {
            throw rechazo(HttpStatus.FORBIDDEN);
        }
        HttpSession session = request.getSession(false);
        Object saved = session == null ? null : session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(saved instanceof SecurityContext context)) {
            throw rechazo(HttpStatus.FORBIDDEN);
        }
        Authentication sessionAuth = context.getAuthentication();
        if (!autorizado(sessionAuth) || !auth.getName().equals(sessionAuth.getName())) {
            throw rechazo(HttpStatus.FORBIDDEN);
        }
    }

    private static boolean autorizado(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken || auth.getPrincipal() == null
                || auth.getName() == null || auth.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(auth.getName())
                || auth.getAuthorities() == null) {
            return false;
        }
        boolean admin = false;
        boolean supervisor = false;
        for (var authority : auth.getAuthorities()) {
            if (authority != null) {
                admin |= "ROLE_ADMINISTRADOR".equals(authority.getAuthority());
                supervisor |= "ROLE_SUPERVISOR".equals(authority.getAuthority());
            }
        }
        return admin && supervisor;
    }

    private static ResponseStatusException rechazo(HttpStatus status) {
        return new ResponseStatusException(status, "No se pudo procesar la solicitud.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<CatalogoError> solicitudRechazada(ResponseStatusException exception) {
        int code = exception.getStatusCode().value();
        HttpStatus status = code == 400 ? HttpStatus.BAD_REQUEST
                : code == 403 ? HttpStatus.FORBIDDEN
                : code == 409 ? HttpStatus.CONFLICT : HttpStatus.INTERNAL_SERVER_ERROR;
        return fallo(status);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<CatalogoError> persistenciaNoDisponible() {
        return fallo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<CatalogoError> fallo(HttpStatus status) {
        String message = status == HttpStatus.BAD_REQUEST ? "Solicitud invalida."
                : status == HttpStatus.FORBIDDEN ? "Operacion no autorizada."
                : status == HttpStatus.CONFLICT ? "Catalogo no disponible."
                : "No se pudo procesar la solicitud.";
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(new CatalogoError(message));
    }

    public record DepartamentoOpcion(Long id, String nombre) { }
    public record CatalogoError(String error) { }
}
