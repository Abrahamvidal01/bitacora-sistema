package com.sistema.bitacora.supervisorcuentas;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Proposal only. Relies on the existing web SecurityFilterChain for CSRF. */
@RestController
@RequestMapping("/supervisor/api/cuentas")
public class GestionCuentasAltaController {
    private static final Set<String> CAMPOS = Set.of("nombreCompleto",
            "correoInstitucional", "password", "departamentoId", "roles");
    private static final Set<String> ROLES =
            Set.of("ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE");
    private final GestionCuentasAltaService service;

    public GestionCuentasAltaController(GestionCuentasAltaService service) {
        this.service = java.util.Objects.requireNonNull(service);
    }

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMINISTRADOR') and hasRole('SUPERVISOR')")
    public ResponseEntity<String> alta(HttpServletRequest request,
            Authentication authentication) {
        exigirSesion(request, authentication);
        GestionCuentasAltaService.AltaCommand command = extraer(request);
        GestionCuentasAltaService.AltaResultado result = service.alta(command, authentication);
        if (result == null || result.usuarioId() == null || result.usuarioId() <= 0) {
            return fallo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        // Numeric ID only; no JSON mapper or user-controlled text interpolation.
        return respuesta(HttpStatus.CREATED, "{\"usuarioId\":" + result.usuarioId() + "}");
    }

    private static void exigirSesion(HttpServletRequest request, Authentication auth) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth != current || !autorizado(auth)) {
            throw new AccessDeniedException("Operacion no autorizada.");
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new AccessDeniedException("Operacion no autorizada.");
        }
        Object saved = session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(saved instanceof SecurityContext context)) {
            throw new AccessDeniedException("Operacion no autorizada.");
        }
        Authentication sessionAuth = context.getAuthentication();
        if (!autorizado(sessionAuth) || !auth.getName().equals(sessionAuth.getName())) {
            throw new AccessDeniedException("Operacion no autorizada.");
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

    private static GestionCuentasAltaService.AltaCommand extraer(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            throw new EntradaInvalida();
        }
        Map<String, String[]> parameters;
        try {
            parameters = request.getParameterMap();
        } catch (IllegalArgumentException malformedParameters) {
            throw new EntradaInvalida();
        }
        Set<String> allowed = new HashSet<>(CAMPOS);
        Object resolved = request.getAttribute(CsrfToken.class.getName());
        String csrfName = null;
        if (resolved instanceof CsrfToken csrf) {
            csrfName = csrf.getParameterName();
            if (csrfName != null && !csrfName.isBlank()) {
                allowed.add(csrfName);
            }
        }
        for (var entry : parameters.entrySet()) {
            String key = entry.getKey();
            String[] values = entry.getValue();
            if (!allowed.contains(key) || values == null || values.length == 0) {
                throw new EntradaInvalida();
            }
            if (!"roles".equals(key) && values.length != 1) {
                throw new EntradaInvalida();
            }
            for (String value : values) {
                if (value == null) {
                    throw new EntradaInvalida();
                }
            }
        }
        String nombre = scalar(parameters, "nombreCompleto");
        String correo = scalar(parameters, "correoInstitucional");
        String password = scalar(parameters, "password"); // Deliberately not trimmed.
        String rawDepartment = scalar(parameters, "departamentoId");
        if (rawDepartment.length() > 19 || !rawDepartment.matches("[1-9][0-9]*")) {
            throw new EntradaInvalida();
        }
        Long departamento;
        try {
            departamento = Long.valueOf(rawDepartment);
        } catch (NumberFormatException invalidNumber) {
            throw new EntradaInvalida();
        }
        String[] rawRoles = parameters.get("roles");
        if (rawRoles == null || rawRoles.length < 1 || rawRoles.length > 3) {
            throw new EntradaInvalida();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String role : rawRoles) {
            if (!ROLES.contains(role) || !roles.add(role)) {
                throw new EntradaInvalida();
            }
        }
        return new GestionCuentasAltaService.AltaCommand(
                nombre, correo, password, departamento, roles);
    }

    private static String scalar(Map<String, String[]> parameters, String key) {
        String[] values = parameters.get(key);
        if (values == null || values.length != 1 || values[0] == null
                || values[0].isEmpty()) {
            throw new EntradaInvalida();
        }
        return values[0];
    }

    @ExceptionHandler(EntradaInvalida.class)
    public ResponseEntity<String> entradaInvalida() {
        return fallo(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> accesoDenegado() {
        return fallo(HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> estadoServicio(ResponseStatusException exception) {
        int status = exception.getStatusCode().value();
        if (status == 400) {
            return fallo(HttpStatus.BAD_REQUEST);
        }
        if (status == 403) {
            return fallo(HttpStatus.FORBIDDEN);
        }
        if (status == 409) {
            return fallo(HttpStatus.CONFLICT);
        }
        return fallo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> conflictoPersistencia() {
        // Not all integrity failures mean duplicate email.
        return fallo(HttpStatus.CONFLICT);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> falloNoEsperado() {
        return fallo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<String> fallo(HttpStatus status) {
        String message = status == HttpStatus.BAD_REQUEST ? "Solicitud invalida."
                : status == HttpStatus.FORBIDDEN ? "Operacion no autorizada."
                : status == HttpStatus.CONFLICT ? "No se pudo completar el alta."
                : "No se pudo procesar la solicitud.";
        return respuesta(status, "{\"error\":\"" + message + "\"}");
    }

    private static ResponseEntity<String> respuesta(HttpStatus status, String body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static final class EntradaInvalida extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
