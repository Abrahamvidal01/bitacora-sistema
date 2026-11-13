package com.sistema.bitacora.controller;

import com.sistema.bitacora.entity.Rol;
import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Unitarias de seleccion de identidad y comprobacion de plantillas, no pruebas HTTP reales.
class IdentidadPanelAdviceTest {
    private UsuarioRepository repositorio;
    private IdentidadPanelAdvice advice;
    private Usuario usuario;
    private ExtendedModelMap modelo;
    private MockHttpServletRequest peticion;

    @BeforeEach void preparar() {
        repositorio = mock(UsuarioRepository.class); advice = new IdentidadPanelAdvice(repositorio);
        usuario = new Usuario(); usuario.setActivo(true);
        usuario.setNombreCompleto("Maria Muñoz Hernandez"); usuario.setCorreoInstitucional("cuenta@prueba.local");
        roles("ADMINISTRADOR"); when(repositorio.findByCorreoInstitucional("cuenta@prueba.local")).thenReturn(Optional.of(usuario));
        modelo = new ExtendedModelMap(); peticion = new MockHttpServletRequest(); peticion.setServletPath("/admin/dashboard");
    }
    private void roles(String... nombres) {
        usuario.setRoles(Arrays.stream(nombres).map(nombre -> {
            Rol rol = new Rol(); rol.setNombreRol(nombre); return rol;
        }).collect(Collectors.toSet()));
    }
    private Authentication sesion(String... roles) {
        return new UsernamePasswordAuthenticationToken("cuenta@prueba.local", "no-usada",
                Arrays.stream(roles).map(rol -> new SimpleGrantedAuthority("ROLE_" + rol)).toList());
    }
    private void comprobar(String nombre, String rol) {
        assertEquals(nombre, modelo.get("nombreUsuarioActual")); assertEquals(rol, modelo.get("rolUsuarioActual"));
        verify(repositorio, never()).save(any(Usuario.class));
    }
    private void prohibido(Authentication sesion) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> advice.prepararIdentidad(sesion, modelo, peticion));
        assertEquals(403, ex.getStatusCode().value()); assertFalse(modelo.containsAttribute("nombreUsuarioActual"));
    }
    @Test void administradorMuestraNombreDeBaseNoCorreoNiTextoFijo() {
        advice.prepararIdentidad(sesion("ADMINISTRADOR"), modelo, peticion);
        comprobar("Maria Muñoz Hernandez", "Administrador general");
    }
    @Test void supervisorMuestraSuNombreYRol() {
        roles("SUPERVISOR"); peticion.setServletPath("/supervisor/dashboard");
        advice.prepararIdentidad(sesion("SUPERVISOR"), modelo, peticion); comprobar("Maria Muñoz Hernandez", "Supervisor");
    }
    @Test void solicitanteMuestraSuNombreYRol() {
        roles("SOLICITANTE"); peticion.setServletPath("/solicitante/dashboard");
        advice.prepararIdentidad(sesion("SOLICITANTE"), modelo, peticion); comprobar("Maria Muñoz Hernandez", "Solicitante");
    }
    @Test void variosRolesMuestranElDelPanelActual() {
        roles("ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE");
        Authentication sesion = sesion("ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE");
        advice.prepararIdentidad(sesion, modelo, peticion); comprobar("Maria Muñoz Hernandez", "Administrador general");
        modelo.clear(); peticion.setServletPath("/supervisor/dashboard");
        advice.prepararIdentidad(sesion, modelo, peticion); comprobar("Maria Muñoz Hernandez", "Supervisor");
        modelo.clear(); peticion.setServletPath("/solicitante/dashboard");
        advice.prepararIdentidad(sesion, modelo, peticion); comprobar("Maria Muñoz Hernandez", "Solicitante");
    }
    @Test void adminEnVistaSupervisorNoRecibeUnRolInventado() {
        peticion.setServletPath("/supervisor/dashboard");
        advice.prepararIdentidad(sesion("ADMINISTRADOR"), modelo, peticion); comprobar("Maria Muñoz Hernandez", "Administrador general");
    }
    @Test void sinSesionNoConsultaCuenta() {
        var ex = assertThrows(ResponseStatusException.class, () -> advice.prepararIdentidad(null, modelo, peticion));
        assertEquals(401, ex.getStatusCode().value()); verifyNoInteractions(repositorio);
    }
    @Test void anonimoNoConsultaCuenta() {
        Authentication anonimo = new AnonymousAuthenticationToken("clave", "anonymousUser", Set.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        var ex = assertThrows(ResponseStatusException.class, () -> advice.prepararIdentidad(anonimo, modelo, peticion));
        assertEquals(401, ex.getStatusCode().value()); verifyNoInteractions(repositorio);
    }
    @Test void cuentaInactivaNoMuestraIdentidad() { usuario.setActivo(false); prohibido(sesion("ADMINISTRADOR")); }
    @Test void cuentaInexistenteNoMuestraIdentidad() {
        when(repositorio.findByCorreoInstitucional("cuenta@prueba.local")).thenReturn(Optional.empty()); prohibido(sesion("ADMINISTRADOR"));
    }
    @Test void rolRetiradoDeBaseNoSeTomaDeSesionVieja() { roles("SOLICITANTE"); prohibido(sesion("ADMINISTRADOR")); }
    @Test void rolQueNoEstaEnSesionNoSeUsaParaElevarPrivilegios() { roles("ADMINISTRADOR", "SOLICITANTE"); prohibido(sesion("SOLICITANTE")); }
    @Test void nombreVacioUsaCorreoDeLaCuenta() {
        usuario.setNombreCompleto(" "); advice.prepararIdentidad(sesion("ADMINISTRADOR"), modelo, peticion);
        comprobar("cuenta@prueba.local", "Administrador general");
    }
    @Test void loginYPanelSelectorNoConsultanIdentidad() {
        for (String ruta : new String[]{"/login", "/", "/panel", "/bitacoras"}) {
            peticion.setServletPath(ruta); advice.prepararIdentidad(null, modelo, peticion);
        }
        verifyNoInteractions(repositorio); assertTrue(modelo.isEmpty());
    }
    @Test void nombreActualizadoSeLeeEnLaSiguientePeticion() {
        advice.prepararIdentidad(sesion("ADMINISTRADOR"), modelo, peticion);
        usuario.setNombreCompleto("Pedro Garcia"); modelo.clear();
        advice.prepararIdentidad(sesion("ADMINISTRADOR"), modelo, peticion); comprobar("Pedro Garcia", "Administrador general");
    }
    @Test void plantillasContienenIdentidadYConservanFuncionesAdmin() throws Exception {
        for (String rol : new String[]{"admin", "supervisor", "solicitante"}) {
            String html;
            try (var entrada = getClass().getResourceAsStream("/templates/" + rol + "/dashboard.html")) {
                assertNotNull(entrada); html = new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertTrue(html.contains("th:text=\"${nombreUsuarioActual}\""));
            assertTrue(html.contains("th:text=\"${rolUsuarioActual}\""));
            assertFalse(html.contains("th:utext=\"${nombreUsuarioActual}\""));
            assertTrue(html.contains("IDENTIDAD ACTUAL DEL PANEL v1"));
            if ("admin".equals(rol)) {
                for (String script : new String[]{"reportes-admin.js", "espacio-eliminar.js", "dashboard-admin.js", "reserva-validacion.js"}) {
                    assertTrue(html.contains(script), "No debe perderse " + script);
                }
            }
        }
    }
}
