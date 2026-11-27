package com.sistema.bitacora.supervisordepartamentos;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Backend only. Requires the separately reviewed audit table before alta is enabled. */
@Service
public class DepartamentoGestionService {
    static final String AUTH = "hasRole('ADMINISTRADOR') and hasRole('SUPERVISOR')";
    static final String ADMIN = "ADMINISTRADOR";
    static final String SUPERVISOR = "SUPERVISOR";
    static final String ACTION = "ALTA_DEPARTAMENTO";
    static final String COUNT_ACTOR = """
        SELECT COUNT(*) FROM usuarios u
        WHERE BINARY LOWER(TRIM(u.correo_institucional)) = BINARY ? AND u.activo = 1
          AND EXISTS (SELECT 1 FROM usuario_roles ur JOIN roles r ON r.id = ur.rol_id
                      WHERE ur.usuario_id = u.id AND BINARY r.nombre_rol = BINARY 'ADMINISTRADOR')
          AND EXISTS (SELECT 1 FROM usuario_roles ur JOIN roles r ON r.id = ur.rol_id
                      WHERE ur.usuario_id = u.id AND BINARY r.nombre_rol = BINARY 'SUPERVISOR')
        """;
    static final String LOCK_ACTOR = """
        SELECT u.id, u.correo_institucional, u.activo FROM usuarios u
        WHERE BINARY LOWER(TRIM(u.correo_institucional)) = BINARY ? ORDER BY u.id FOR UPDATE
        """;
    static final String LOCK_ROLES = """
        SELECT r.nombre_rol FROM usuario_roles ur JOIN roles r ON r.id = ur.rol_id
        WHERE ur.usuario_id = ? ORDER BY r.id, ur.rol_id FOR UPDATE
        """;
    static final String LIST = """
        SELECT id, nombre_departamento, clave_departamento, activo, cupo_mensual
        FROM departamentos ORDER BY nombre_departamento, id LIMIT 2001
        """;
    static final String DUPLICATE =
        "SELECT COUNT(*) FROM departamentos WHERE nombre_departamento = ?";
    static final String INSERT =
        "INSERT INTO departamentos (nombre_departamento, clave_departamento, activo, cupo_mensual) VALUES (?, ?, 1, 1)";
    static final String AUDIT = """
        INSERT INTO historial_departamentos
        (departamento_id, realizado_por_usuario_id, tipo_accion,
         nombre_anterior, nombre_nuevo, clave_anterior, clave_nueva,
         activo_anterior, activo_nuevo, cupo_anterior, cupo_nuevo, fecha_evento_utc)
        VALUES (?, ?, ?, NULL, ?, NULL, ?, NULL, 1, NULL, 1, ?)
        """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DepartamentoGestionService(JdbcTemplate jdbc, @Qualifier("relojReservas") Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record AltaDepartamento(String nombre, String clave) {
        @Override public String toString() { return "AltaDepartamento[REDACTADO]"; }
    }
    public record DepartamentoVista(long id, String nombre, String clave,
                                    boolean activo, int cupoMensual) { }
    private record Actor(long id, String correo, Integer activo) { }

    @PreAuthorize(AUTH)
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW,
                   isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public List<DepartamentoVista> listar(Authentication authentication) {
        String identity = identity(authentication);
        Long count = jdbc.queryForObject(COUNT_ACTOR, Long.class, identity);
        if (count == null || count != 1L) throw forbidden();
        List<DepartamentoVista> rows = jdbc.query(LIST, (RowMapper<DepartamentoVista>) (rs, n) -> {
            long id = rs.getLong("id");
            if (rs.wasNull() || id <= 0) throw inconsistent();
            String nombre = rs.getString("nombre_departamento");
            String clave = rs.getString("clave_departamento");
            Integer activo = nullableInt(rs, "activo");
            Integer cupo = nullableInt(rs, "cupo_mensual");
            if (!validStoredText(nombre, 100, false) || !validStoredText(clave, 20, true)
                    || activo == null || (activo != 0 && activo != 1)
                    || cupo == null || (cupo != 1 && cupo != 4)) throw inconsistent();
            return new DepartamentoVista(id, nombre, clave, activo == 1, cupo);
        });
        if (rows.size() > 2000) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "El listado supera 2000 departamentos; se requiere paginacion explicita.");
        return List.copyOf(rows);
    }

    @PreAuthorize(AUTH)
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
                   rollbackFor = Exception.class, timeout = 30)
    public DepartamentoVista alta(AltaDepartamento command, Authentication authentication) {
        String identity = identity(authentication);
        if (command == null) throw invalid();
        String nombre = normalized(command.nombre(), 100, false);
        String clave = normalized(command.clave(), 20, true);
        long actorId = lockActor(identity);
        Long duplicates = jdbc.queryForObject(DUPLICATE, Long.class, nombre);
        if (duplicates == null || duplicates < 0) throw inconsistent();
        if (duplicates > 0) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "El departamento ya existe, activo o inactivo; no se reactiva automaticamente.");

        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        int changed = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, nombre);
            statement.setString(2, clave);
            return statement;
        }, keys);
        if (changed != 1) throw new IllegalStateException("Alta sin una fila insertada.");
        long id = positiveKey(keys.getKey());
        int audited = jdbc.update(AUDIT, id, actorId, ACTION, nombre, clave,
                                  LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        if (audited != 1) throw new IllegalStateException("Auditoria sin una fila insertada.");
        return new DepartamentoVista(id, nombre, clave, true, 1);
    }

    private long lockActor(String identity) {
        List<Actor> actors = jdbc.query(LOCK_ACTOR, (RowMapper<Actor>) (rs, n) -> {
            long id = rs.getLong("id");
            if (rs.wasNull()) id = 0;
            return new Actor(id, rs.getString("correo_institucional"), nullableInt(rs, "activo"));
        }, identity);
        if (actors.size() != 1) throw forbidden();
        Actor actor = actors.getFirst();
        if (actor.id() <= 0 || actor.activo() == null || actor.activo() != 1
                || actor.correo() == null || !identity.equals(canonicalIdentity(actor.correo()))) throw forbidden();
        List<String> roles = jdbc.query(LOCK_ROLES,
            (RowMapper<String>) (rs, n) -> rs.getString("nombre_rol"), actor.id());
        if (!roles.contains(ADMIN) || !roles.contains(SUPERVISOR)) throw forbidden();
        return actor.id();
    }

    private static String identity(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication != SecurityContextHolder.getContext().getAuthentication()
                || authentication instanceof AnonymousAuthenticationToken) throw forbidden();
        Object principal = authentication.getPrincipal();
        if (principal == null || (principal instanceof String value
                && "anonymousUser".equalsIgnoreCase(value.strip()))) throw forbidden();
        var authorities = authentication.getAuthorities();
        if (authorities == null) throw forbidden();
        boolean admin = authorities.stream()
            .anyMatch(a -> a != null && ("ROLE_" + ADMIN).equals(a.getAuthority()));
        boolean supervisor = authorities.stream()
            .anyMatch(a -> a != null && ("ROLE_" + SUPERVISOR).equals(a.getAuthority()));
        if (!admin || !supervisor) throw forbidden();
        return canonicalIdentity(authentication.getName());
    }

    private static String canonicalIdentity(String value) {
        if (value == null || !safeUnicode(value)) throw forbidden();
        String canonical = value.strip().toLowerCase(Locale.ROOT);
        if (canonical.isEmpty() || "anonymoususer".equals(canonical)
                || canonical.codePointCount(0, canonical.length()) > 100
                || canonical.codePoints().anyMatch(c -> Character.isWhitespace(c)
                    || Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT)) throw forbidden();
        return canonical;
    }

    private static String normalized(String value, int max, boolean optional) {
        if (value == null) {
            if (optional) return null;
            throw invalid();
        }
        if (!safeUnicode(value)) throw invalid();
        if (optional && value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            if (optional) return null;
            throw invalid();
        }
        if (normalized.codePointCount(0, normalized.length()) > max) throw invalid();
        return normalized;
    }

    private static boolean validStoredText(String value, int max, boolean optional) {
        if (value == null) return optional;
        return safeUnicode(value) && value.codePointCount(0, value.length()) <= max
            && (optional || !value.strip().isEmpty());
    }

    private static boolean safeUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(c) || Character.isISOControl(c)) return false;
        }
        return true;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static long positiveKey(Number key) {
        if (key == null) throw new IllegalStateException("Alta sin identificador generado.");
        try {
            long id = new BigDecimal(key.toString()).longValueExact();
            if (id <= 0) throw new ArithmeticException("nonpositive");
            return id;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalStateException("Identificador generado invalido.", exception);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre o clave no validos.");
    }
    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Se requiere usuario activo con ambos roles vigentes.");
    }
    private static ResponseStatusException inconsistent() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Datos de departamentos inconsistentes; requieren revision.");
    }
}
