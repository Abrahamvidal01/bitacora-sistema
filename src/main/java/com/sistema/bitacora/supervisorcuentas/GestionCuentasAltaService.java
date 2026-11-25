package com.sistema.bitacora.supervisorcuentas;

import com.sistema.bitacora.entity.Departamento;
import com.sistema.bitacora.entity.Rol;
import com.sistema.bitacora.entity.Usuario;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Internal stage only. No endpoint or configuration changes. Invoke through the Spring proxy. */
@Service
public class GestionCuentasAltaService {
    private static final Set<String> ROLES_PERMITIDOS =
            Set.of("ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE");
    private static final ZoneId ZONA_AUDITORIA = ZoneId.of("America/Mexico_City");
    private static final Pattern LOCAL_CORREO =
            Pattern.compile("[a-z0-9](?:[a-z0-9._%+-]*[a-z0-9])?");
    private static final Pattern ETIQUETA_DOMINIO =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern TLD = Pattern.compile("[a-z]{2,63}");
    private final EntityManager em;
    private final PasswordEncoder passwordEncoder;
    private final Clock reloj;

    public GestionCuentasAltaService(EntityManager em, PasswordEncoder passwordEncoder,
            @Qualifier("relojReservas") Clock reloj) {
        this.em = Objects.requireNonNull(em);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.reloj = Objects.requireNonNull(reloj);
    }

    @PreAuthorize("hasRole('ADMINISTRADOR') and hasRole('SUPERVISOR')")
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class, timeout = 30)
    public AltaResultado alta(AltaCommand comando, Authentication authentication) {
        String correoActor = autenticarSesionActual(authentication);
        DatosValidados datos = validar(comando);
        long actorId = bloquearYValidarActor(correoActor);

        Departamento departamento = em.find(Departamento.class, datos.departamentoId(),
                LockModeType.PESSIMISTIC_WRITE,
                Map.of("jakarta.persistence.lock.timeout", 5000));
        if (departamento == null || !Boolean.TRUE.equals(departamento.getActivo())) {
            throw error(HttpStatus.BAD_REQUEST, "Departamento inexistente o inactivo.");
        }
        List<Rol> encontrados = em.createQuery("""
                SELECT r FROM Rol r WHERE r.nombreRol IN :nombres ORDER BY r.id
                """, Rol.class)
                .setParameter("nombres", datos.roles())
                .setLockMode(LockModeType.PESSIMISTIC_READ)
                .setHint("jakarta.persistence.lock.timeout", 5000)
                .getResultList();
        Set<String> nombresEncontrados = new HashSet<>();
        Set<Rol> rolesSeleccionados = new LinkedHashSet<>();
        for (Rol rol : encontrados) {
            String nombreRol = rol == null ? null : rol.getNombreRol();
            if (nombreRol == null || !datos.roles().contains(nombreRol)
                    || !nombresEncontrados.add(nombreRol)) {
                throw error(HttpStatus.CONFLICT, "Catalogo de roles inconsistente.");
            }
            rolesSeleccionados.add(rol);
        }
        if (!nombresEncontrados.equals(datos.roles())) {
            throw error(HttpStatus.CONFLICT, "Faltan roles solicitados en el catalogo.");
        }

        Long existentes = em.createQuery("""
                SELECT COUNT(u) FROM Usuario u WHERE u.correoInstitucional = :correo
                """, Long.class)
                .setParameter("correo", datos.correo())
                .getSingleResult();
        if (existentes == null || existentes < 0) {
            throw error(HttpStatus.CONFLICT, "No se pudo comprobar la unicidad.");
        }
        if (existentes > 0) {
            throw error(HttpStatus.CONFLICT, "La cuenta ya existe.");
        }

        String hash = passwordEncoder.encode(comando.password());
        if (hash == null || hash.isBlank() || hash.length() > 255) {
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo proteger la credencial.");
        }
        Usuario usuario = new Usuario();
        usuario.setNombreCompleto(datos.nombre());
        usuario.setCorreoInstitucional(datos.correo());
        usuario.setPasswordHash(hash);
        usuario.setDepartamento(departamento);
        usuario.setRoles(rolesSeleccionados);
        usuario.setActivo(Boolean.TRUE);
        em.persist(usuario);
        em.flush(); // IDENTITY and DB uniqueness/FKs remain the final race control.
        Long usuarioId = usuario.getId();
        if (usuarioId == null || usuarioId <= 0) {
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "No se genero la cuenta.");
        }

        String detalle = "roles=" + String.join(",", new TreeSet<>(datos.roles()))
                + "; departamentoId=" + datos.departamentoId();
        LocalDateTime fecha = LocalDateTime.ofInstant(reloj.instant(), ZONA_AUDITORIA);
        int filas = em.createNativeQuery("""
                INSERT INTO historial_usuarios
                    (usuario_afectado_id, realizado_por_usuario_id, tipo_accion,
                     descripcion_detalle, fecha_evento)
                VALUES (:afectado, :actor, :accion, :detalle, :fecha)
                """)
                .setParameter("afectado", usuarioId)
                .setParameter("actor", actorId)
                .setParameter("accion", "ALTA_CUENTA")
                .setParameter("detalle", detalle)
                .setParameter("fecha", Timestamp.valueOf(fecha))
                .executeUpdate();
        if (filas != 1) {
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo registrar el alta.");
        }
        em.flush(); // Explicit synchronization after the audit INSERT, before returning.
        // Commit occurs in the transaction proxy, after this method returns.
        return new AltaResultado(usuarioId);
    }

    private String autenticarSesionActual(Authentication authentication) {
        Authentication actual = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication != actual
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getPrincipal() == null) {
            throw denegado();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof String texto
                && (texto.isBlank() || "anonymousUser".equalsIgnoreCase(texto))) {
            throw denegado();
        }
        boolean administrador = false;
        boolean supervisor = false;
        if (authentication.getAuthorities() == null) {
            throw denegado();
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority != null) {
                administrador |= "ROLE_ADMINISTRADOR".equals(authority.getAuthority());
                supervisor |= "ROLE_SUPERVISOR".equals(authority.getAuthority());
            }
        }
        if (!administrador || !supervisor) {
            throw denegado();
        }
        String nombre = authentication.getName();
        if (!unicodeSinControles(nombre)) {
            throw denegado();
        }
        String correo = nombre.trim().toLowerCase(Locale.ROOT);
        if (!correoValido(correo)) {
            throw denegado();
        }
        return correo;
    }

    private long bloquearYValidarActor(String correoActor) {
        List<?> filas = em.createNativeQuery("""
                SELECT id, correo_institucional, activo
                FROM usuarios WHERE correo_institucional = :correo
                ORDER BY id FOR UPDATE
                """)
                .setParameter("correo", correoActor)
                .getResultList();
        if (filas.size() != 1 || !(filas.getFirst() instanceof Object[] fila)
                || fila.length != 3 || !(fila[0] instanceof Number numero)
                || !(fila[1] instanceof String correoDb)
                || !correoActor.equals(correoDb.trim().toLowerCase(Locale.ROOT))
                || !booleanoVerdadero(fila[2])) {
            throw denegado();
        }
        long actorId = numero.longValue();
        if (actorId <= 0) {
            throw denegado();
        }
        List<?> roles = em.createNativeQuery("""
                SELECT r.nombre_rol
                FROM usuario_roles ur JOIN roles r ON r.id = ur.rol_id
                WHERE ur.usuario_id = :actor
                ORDER BY r.id FOR UPDATE
                """)
                .setParameter("actor", actorId)
                .getResultList();
        if (!roles.contains("ADMINISTRADOR") || !roles.contains("SUPERVISOR")) {
            throw denegado();
        }
        return actorId;
    }

    private static boolean booleanoVerdadero(Object value) {
        return Boolean.TRUE.equals(value)
                || (value instanceof Number n && n.intValue() == 1)
                || (value instanceof byte[] bytes && bytes.length == 1 && bytes[0] == 1);
    }

    private static DatosValidados validar(AltaCommand comando) {
        if (comando == null || !unicodeSinControles(comando.nombreCompleto())) {
            throw invalido();
        }
        String nombre = comando.nombreCompleto().strip();
        if (nombre.isEmpty() || nombre.codePointCount(0, nombre.length()) > 150) {
            throw invalido();
        }
        if (!unicodeSinControles(comando.correoInstitucional())) {
            throw invalido();
        }
        String correo = comando.correoInstitucional().trim().toLowerCase(Locale.ROOT);
        if (!correoValido(correo)) {
            throw invalido();
        }
        String password = comando.password();
        if (!unicodeSinControles(password)
                || password.codePointCount(0, password.length()) < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalido();
        }
        if (comando.departamentoId() == null || comando.departamentoId() <= 0
                || comando.roles() == null || comando.roles().isEmpty()
                || comando.roles().size() > 3) {
            throw invalido();
        }
        Set<String> roles = new TreeSet<>();
        for (String rol : comando.roles()) {
            if (rol == null || !ROLES_PERMITIDOS.contains(rol)) {
                throw invalido();
            }
            roles.add(rol);
        }
        return new DatosValidados(nombre, correo, comando.departamentoId(), Set.copyOf(roles));
    }

    private static boolean correoValido(String correo) {
        if (correo == null || correo.isEmpty() || correo.length() > 100) {
            return false;
        }
        for (int i = 0; i < correo.length(); i++) {
            if (correo.charAt(i) > 127) {
                return false;
            }
        }
        int arroba = correo.indexOf('@');
        if (arroba < 1 || arroba > 64 || arroba != correo.lastIndexOf('@')) {
            return false;
        }
        String local = correo.substring(0, arroba);
        String dominio = correo.substring(arroba + 1);
        if (!LOCAL_CORREO.matcher(local).matches() || local.contains("..")) {
            return false;
        }
        String[] etiquetas = dominio.split("[.]", -1);
        if (etiquetas.length < 2 || !TLD.matcher(etiquetas[etiquetas.length - 1]).matches()) {
            return false;
        }
        for (String etiqueta : etiquetas) {
            if (!ETIQUETA_DOMINIO.matcher(etiqueta).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean unicodeSinControles(String texto) {
        if (texto == null) {
            return false;
        }
        for (int i = 0; i < texto.length();) {
            char ch = texto.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= texto.length() || !Character.isLowSurrogate(texto.charAt(i + 1))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(ch)) {
                return false;
            }
            int cp = texto.codePointAt(i);
            int tipo = Character.getType(cp);
            if (Character.isISOControl(cp) || tipo == Character.FORMAT
                    || tipo == Character.LINE_SEPARATOR || tipo == Character.PARAGRAPH_SEPARATOR) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    private static ResponseStatusException error(HttpStatus estado, String mensaje) {
        return new ResponseStatusException(estado, mensaje);
    }
    private static ResponseStatusException invalido() {
        return error(HttpStatus.BAD_REQUEST, "Datos de alta invalidos.");
    }
    private static ResponseStatusException denegado() {
        return error(HttpStatus.FORBIDDEN, "Operacion no autorizada.");
    }

    /** No actor ID, active flag, hash, history or arbitrary-field map in this contract. */
    public record AltaCommand(String nombreCompleto, String correoInstitucional,
            String password, Long departamentoId, Set<String> roles) {
        public AltaCommand {
            // Do not use Set.copyOf here: null/null elements must reach controlled 400 validation.
            roles = roles == null ? null
                    : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
        }
        @Override public String toString() { return "AltaCommand[contenido=REDACTADO]"; }
    }
    /** ID only. No name, email, password or hash is returned. */
    public record AltaResultado(Long usuarioId) { }
    private record DatosValidados(String nombre, String correo, Long departamentoId,
            Set<String> roles) {
        @Override public String toString() { return "DatosValidados[contenido=REDACTADO]"; }
    }
}
