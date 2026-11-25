package com.sistema.bitacora.supervisorbitacora;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Static proposal: compile and review locally before use. MySQL/MariaDB SQL. */
@RestController
@RequestMapping("/supervisor/api")
public class SupervisorIndicadoresController {
    static final long MAX_SAFE_INTEGER = 9007199254740991L;
    // Verify these existing PK/FK names against the local mappings before compiling.
    static final String ACTOR_SQL =
        "SELECT COUNT(*) FROM usuarios u " +
        "WHERE u.correo_institucional = :correo AND u.activo = 1 " +
        "AND EXISTS (SELECT 1 FROM usuario_roles ur " +
        "JOIN roles r ON r.id = ur.rol_id " +
        "WHERE ur.usuario_id = u.id AND BINARY r.nombre_rol = 'SUPERVISOR')";
    static final String MONTH_SQL =
        "SELECT COUNT(*), " +
        "COALESCE(SUM(CASE WHEN BINARY c.modalidad IN " +
        "('NORMAL','URGENTE_INVENTARIO','URGENTE_REQUISICION') THEN 1 ELSE 0 END),0), " +
        "COALESCE(SUM(CASE WHEN BINARY s.estatus_solicitud IN " +
        "('APROBADA','PENDIENTE','RECHAZADA','CANCELADA') THEN 1 ELSE 0 END),0), " +
        "COALESCE(SUM(CASE WHEN BINARY s.estatus_solicitud = 'PENDIENTE' " +
        "AND BINARY c.modalidad = 'URGENTE_REQUISICION' THEN 1 ELSE 0 END),0), " +
        "COALESCE(SUM(CASE WHEN BINARY s.estatus_solicitud = 'APROBADA' THEN 1 ELSE 0 END),0), " +
        "COALESCE(SUM(CASE WHEN BINARY c.modalidad IN " +
        "('URGENTE_INVENTARIO','URGENTE_REQUISICION') THEN 1 ELSE 0 END),0), " +
        "COALESCE(SUM(CASE WHEN BINARY c.modalidad = 'NORMAL' THEN 1 ELSE 0 END),0) " +
        "FROM solicitudes_espacio s " +
        "LEFT JOIN confirmaciones_reserva c ON c.solicitud_id = s.id " +
        "WHERE s.fecha_evento >= :inicio AND s.fecha_evento < :fin";
    static final String ACTIVE_SQL = "SELECT COUNT(*) FROM usuarios WHERE activo = 1";
    private final EntityManager entityManager;
    private final TransactionTemplate reads;

    public SupervisorIndicadoresController(EntityManager entityManager,
                                            PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.reads.setTimeout(30);
    }

    @GetMapping("/indicadores")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<?> indicadores(@RequestParam(name = "mes", required = false) String mes,
                                         Authentication authentication,
                                         HttpServletRequest request) {
        if (!authenticated(authentication)) {
            return error(401, "SESION_REQUERIDA", "Inicia una sesion de supervisor.");
        }
        if (!supervisor(authentication) || !matchingSession(request, authentication)) {
            return error(403, "ACCESO_DENEGADO", "Se requiere una sesion de supervisor vigente.");
        }
        final YearMonth month;
        try {
            month = parseMes(mes);
        } catch (IllegalArgumentException ex) {
            return error(400, "MES_INVALIDO", "Usa un mes entre 1000-01 y 9998-12 con formato YYYY-MM.");
        }
        try {
            Indicadores result = reads.execute(status -> {
                long actors = safeCount(query(ACTOR_SQL)
                    .setParameter("correo", authentication.getName()).getSingleResult());
                // EXISTS prevents duplicate role memberships from multiplying this count.
                // Ambiguous account names are denied, not arbitrarily selected.
                if (actors != 1L) {
                    throw new ActorDenied();
                }
                Object raw = query(MONTH_SQL)
                    .setParameter("inicio", Date.valueOf(month.atDay(1)))
                    .setParameter("fin", Date.valueOf(month.plusMonths(1).atDay(1)))
                    .getSingleResult();
                if (!(raw instanceof Object[]) || ((Object[]) raw).length != 7) {
                    throw new DatosNoDisponibles();
                }
                Object[] row = (Object[]) raw;
                long total = safeCount(row[0]);
                long classified = safeCount(row[1]);
                long recognized = safeCount(row[2]);
                long pending = safeCount(row[3]);
                long approved = safeCount(row[4]);
                long urgent = safeCount(row[5]);
                long normal = safeCount(row[6]);
                // Missing confirmation rows, NULL/unknown modalities or unknown states fail closed.
                // A LEFT JOIN is essential: INNER JOIN would silently hide missing metadata.
                if (classified != total || recognized != total || pending > urgent ||
                    approved > total || urgent > total || normal > total ||
                    urgent != total - normal) {
                    throw new DatosNoDisponibles();
                }
                long active = safeCount(query(ACTIVE_SQL).getSingleResult());
                return new Indicadores(month.toString(), pending, approved, urgent, normal, active);
            });
            if (result == null) {
                throw new DatosNoDisponibles();
            }
            return noStore(200, result);
        } catch (ActorDenied ex) {
            return error(403, "ACCESO_DENEGADO", "La cuenta no tiene acceso de supervisor activo.");
        } catch (DatosNoDisponibles ex) {
            return error(409, "DATOS_NO_DISPONIBLES", "No es posible publicar indicadores confiables para ese mes.");
        } catch (RuntimeException ex) {
            // No query text, actor identity, SQL diagnostics or exception messages in the response.
            return error(503, "CONSULTA_NO_DISPONIBLE", "No fue posible consultar los indicadores. Intenta de nuevo.");
        }
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> accessDenied(AccessDeniedException ignored) {
        return error(403, "ACCESO_DENEGADO", "Se requiere acceso de supervisor.");
    }

    private Query query(String sql) {
        return entityManager.createNativeQuery(sql)
            .setHint("jakarta.persistence.query.timeout", 30000);
    }

    /** Independent strict month parser; no clock, database, request or timezone dependency. */
    static YearMonth parseMes(String value) {
        if (value == null || !value.matches("[1-9][0-9]{3}-(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("Mes invalido");
        }
        YearMonth month = YearMonth.parse(value);
        if (month.getYear() < 1000 || month.getYear() > 9998) {
            throw new IllegalArgumentException("Mes fuera de rango");
        }
        return month;
    }

    static long safeCount(Object value) {
        if (!(value instanceof Number)) {
            throw new DatosNoDisponibles();
        }
        try {
            long count = new BigDecimal(value.toString()).longValueExact();
            if (count < 0 || count > MAX_SAFE_INTEGER) {
                throw new DatosNoDisponibles();
            }
            return count;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new DatosNoDisponibles();
        }
    }

    private static boolean authenticated(Authentication actor) {
        return actor != null && actor.isAuthenticated() &&
            !(actor instanceof AnonymousAuthenticationToken) &&
            actor.getName() != null && !actor.getName().trim().isEmpty();
    }

    private static boolean supervisor(Authentication actor) {
        return authenticated(actor) && actor.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_SUPERVISOR".equals(authority.getAuthority()));
    }

    private static boolean matchingSession(HttpServletRequest request, Authentication actor) {
        if (request == null) {
            return false;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(value instanceof SecurityContext)) {
            return false;
        }
        Authentication saved = ((SecurityContext) value).getAuthentication();
        return supervisor(saved) && actor.getName().equals(saved.getName());
    }

    private static ResponseEntity<?> error(int status, String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("codigo", code);
        body.put("mensaje", message);
        return noStore(status, body);
    }

    private static ResponseEntity<?> noStore(int status, Object body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache").header("Expires", "0").varyBy("Cookie").body(body);
    }

    private static final class ActorDenied extends RuntimeException { }
    static final class DatosNoDisponibles extends RuntimeException { }

    public static final class Indicadores {
        public final String mes;
        public final long pendientesDictamen;
        public final long aprobadasMes;
        public final long urgentesMes;
        public final long normalesMes;
        public final long usuariosActivosActual;

        public Indicadores(String mes, long pendientesDictamen, long aprobadasMes,
                           long urgentesMes, long normalesMes, long usuariosActivosActual) {
            this.mes = mes;
            this.pendientesDictamen = pendientesDictamen;
            this.aprobadasMes = aprobadasMes;
            this.urgentesMes = urgentesMes;
            this.normalesMes = normalesMes;
            this.usuariosActivosActual = usuariosActivosActual;
        }
    }
}
