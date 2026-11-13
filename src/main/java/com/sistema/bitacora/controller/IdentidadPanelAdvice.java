package com.sistema.bitacora.controller;

import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.repository.UsuarioRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Independiente de NavegacionAdvice: no cambia enlaces de regreso ni los controladores.
@ControllerAdvice(assignableTypes = ViewController.class)
public class IdentidadPanelAdvice {
    private static final Map<String, String> PANELES = Map.of(
            "/admin/dashboard", "ADMINISTRADOR",
            "/supervisor/dashboard", "SUPERVISOR",
            "/solicitante/dashboard", "SOLICITANTE");
    private static final Map<String, String> TITULOS = Map.of(
            "ADMINISTRADOR", "Administrador general",
            "SUPERVISOR", "Supervisor",
            "SOLICITANTE", "Solicitante");
    private final UsuarioRepository usuarios;

    public IdentidadPanelAdvice(UsuarioRepository usuarios) { this.usuarios = usuarios; }

    @ModelAttribute
    public void prepararIdentidad(Authentication autenticacion, Model modelo, HttpServletRequest peticion) {
        String objetivo = PANELES.get(peticion.getServletPath());
        // No consultar usuarios en /login, /panel, /bitacoras ni otras pantallas publicas.
        if (objetivo == null) return;
        if (autenticacion == null || !autenticacion.isAuthenticated()
                || autenticacion instanceof AnonymousAuthenticationToken
                || autenticacion.getName() == null || autenticacion.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Debes iniciar sesion");
        }
        Usuario usuario = usuarios.findByCorreoInstitucional(autenticacion.getName())
                .orElseThrow(() -> prohibido());
        if (!Boolean.TRUE.equals(usuario.getActivo())) throw prohibido();
        Set<String> rolesActuales = usuario.getRoles() == null ? Set.of()
                : usuario.getRoles().stream().filter(rol -> rol != null && rol.getNombreRol() != null)
                .map(rol -> rol.getNombreRol()).filter(TITULOS::containsKey).collect(Collectors.toSet());
        Set<String> rolesEfectivos = autenticacion.getAuthorities().stream()
                .map(autoridad -> autoridad.getAuthority())
                .filter(autoridad -> autoridad != null && autoridad.startsWith("ROLE_"))
                .map(autoridad -> autoridad.substring(5))
                .filter(rolesActuales::contains).collect(Collectors.toSet());
        String rol;
        if (rolesEfectivos.contains(objetivo)) {
            rol = objetivo;
        } else if ("SUPERVISOR".equals(objetivo) && rolesEfectivos.contains("ADMINISTRADOR")) {
            // La seguridad existente permite ADMINISTRADOR en /supervisor/**.
            // Mostrar su rol real, sin inventarle un nombramiento de supervisor.
            rol = "ADMINISTRADOR";
        } else {
            throw prohibido();
        }
        String nombre = usuario.getNombreCompleto();
        if (nombre == null || nombre.isBlank()) nombre = usuario.getCorreoInstitucional();
        if (nombre == null || nombre.isBlank()) nombre = autenticacion.getName();
        modelo.addAttribute("nombreUsuarioActual", nombre);
        modelo.addAttribute("rolUsuarioActual", TITULOS.get(rol));
        // Solo lectura: no modifica cuenta, rol, nombramiento ni autor de reservas anteriores.
    }

    private static ResponseStatusException prohibido() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "La cuenta debe estar activa y conservar un rol autorizado para este panel");
    }
}
