package com.sistema.bitacora.supervisorrevision;

import com.sistema.bitacora.config.SecurityConfig;
import com.sistema.bitacora.controller.IdentidadPanelAdvice;
import com.sistema.bitacora.controller.TestController;
import com.sistema.bitacora.entity.Rol;
import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.repository.UsuarioRepository;
import com.sistema.bitacora.security.JwtAuthenticationFilter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Prueba aislada, sin Spring Boot, escaneo de componentes, DataSource ni puerto TCP.
 * Usa las cadenas reales de SecurityConfig y el ValidacionSesionFilter real que estas crean.
 * Las cuentas/roles existen SOLO en memoria; no abre ni modifica cuentas de MariaDB.
 * No sustituye login real, JWT real, CSRF ni la integracion transaccional de urgencias.
 */
class PermisosModulosSieteCombinacionesTest {
    private static final String EMAIL = "matriz-permisos@example.invalid";
    private static final String[] ROLES = {"ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE"};
    private static final String[] PREFIXES = {"/admin", "/supervisor", "/solicitante"};
    private static final String[] TITLES = {"Administrador general", "Supervisor", "Solicitante"};
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private CuentasEnMemoria cuentas;

    @BeforeEach
    void prepararContextoSinBaseDeDatos() {
        SecurityContextHolder.clearContext();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(ConfiguracionAislada.class);
        context.refresh();
        assertTrue(context.getBeansOfType(DataSource.class).isEmpty(), "Este test no admite DataSource");
        assertTrue(context.getBeansOfType(EntityManager.class).isEmpty(), "Este test no admite EntityManager");
        cuentas = context.getBean(CuentasEnMemoria.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cerrarContexto() {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    static Stream<Arguments> combinaciones() {
        return Stream.of(
                Arguments.of("solo administrador", new String[]{"ADMINISTRADOR"}, 200, 403, 403),
                Arguments.of("solo supervisor", new String[]{"SUPERVISOR"}, 403, 200, 403),
                Arguments.of("solo solicitante", new String[]{"SOLICITANTE"}, 403, 403, 200),
                Arguments.of("administrador y supervisor", new String[]{"ADMINISTRADOR", "SUPERVISOR"}, 200, 200, 403),
                Arguments.of("administrador y solicitante", new String[]{"ADMINISTRADOR", "SOLICITANTE"}, 200, 403, 200),
                Arguments.of("supervisor y solicitante", new String[]{"SUPERVISOR", "SOLICITANTE"}, 403, 200, 200),
                Arguments.of("los tres roles", new String[]{"ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE"}, 200, 200, 200));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combinaciones")
    void permisosExactosDeUrlServicioEIdentidad(String caso, String[] roles, int admin, int supervisor, int solicitante) throws Exception {
        UserDetails details = User.withUsername(EMAIL).password("no-se-utiliza-para-login").roles(roles).build();
        cuentas.actuales.put(EMAIL, details);
        int[] expected = {admin, supervisor, solicitante};
        for (int i = 0; i < PREFIXES.length; i++) {
            int before = cuentas.lecturas.get();
            var response = mvc.perform(get(PREFIXES[i] + "/permisos-it").with(user(details)))
                    .andReturn().getResponse();
            assertTrue(cuentas.lecturas.get() > before,
                    "La peticion debe atravesar la recarga real de ValidacionSesionFilter: " + caso);
            assertEquals(expected[i], response.getStatus(), caso + " -> " + PREFIXES[i]);
            if (expected[i] == 200) assertEquals("ok", response.getContentAsString());
        }

        // Comprobacion unitaria del guard interno real. Usuario es transitorio, no una fila escrita.
        Usuario usuario = usuario(roles);
        Authentication auth = autenticacion(details);
        UrgenciaRevisionService service = servicioSinDependenciasReales();
        if (Arrays.asList(roles).contains("SUPERVISOR")) {
            assertDoesNotThrow(() -> verificarActor(service, usuario, auth), caso);
        } else {
            assertForbidden(() -> verificarActor(service, usuario, auth));
        }

        // El consejo real de identidad no debe inventar SUPERVISOR a partir de ADMINISTRADOR.
        UsuarioRepository repository = mock(UsuarioRepository.class);
        when(repository.findByCorreoInstitucional(EMAIL)).thenReturn(Optional.of(usuario));
        IdentidadPanelAdvice advice = new IdentidadPanelAdvice(repository);
        Set<String> actuales = Set.copyOf(Arrays.asList(roles));
        for (int i = 0; i < PREFIXES.length; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServletPath(PREFIXES[i] + "/dashboard");
            ExtendedModelMap model = new ExtendedModelMap();
            if (actuales.contains(ROLES[i])) {
                advice.prepararIdentidad(auth, model, request);
                assertEquals(TITLES[i], model.get("rolUsuarioActual"), caso);
            } else {
                assertForbidden(() -> advice.prepararIdentidad(auth, model, request));
            }
        }
    }

    @Test
    void autoridadSupervisorInventadaNoSustituyeRolActualDelModelo() {
        UserDetails session = User.withUsername(EMAIL).password("no-se-utiliza").roles("SUPERVISOR").build();
        // Representa desacuerdo entre la sesion y el modelo cargado, no una cuenta real.
        Usuario soloAdministrador = usuario(new String[]{"ADMINISTRADOR"});
        assertForbidden(() -> verificarActor(servicioSinDependenciasReales(), soloAdministrador, autenticacion(session)));
    }

    @Test
    void anotacionesRealesDelModuloExigenSoloSupervisor() throws Exception {
        String expression = "hasRole('SUPERVISOR')";
        assertEquals(expression, UrgenciaRevisionService.class.getAnnotation(PreAuthorize.class).value());
        assertEquals(expression, UrgenciaRevisionController.class.getAnnotation(PreAuthorize.class).value());
        assertEquals(expression, TestController.class.getMethod("testSupervisor").getAnnotation(PreAuthorize.class).value());
        // Esto verifica las anotaciones; no afirma haber probado todos los metodos por proxy/HTTP.
    }

    private static Usuario usuario(String[] names) {
        Usuario usuario = new Usuario();
        usuario.setActivo(true);
        usuario.setCorreoInstitucional(EMAIL);
        usuario.setNombreCompleto("Identidad sintetica de prueba");
        usuario.setRoles(Arrays.stream(names).map(name -> new Rol(null, name, "solo en memoria"))
                .collect(Collectors.toCollection(HashSet::new)));
        return usuario;
    }

    private static Authentication autenticacion(UserDetails details) {
        return new UsernamePasswordAuthenticationToken(details.getUsername(), null, details.getAuthorities());
    }

    private static UrgenciaRevisionService servicioSinDependenciasReales() {
        return new UrgenciaRevisionService(mock(EntityManager.class), mock(UsuarioRepository.class),
                mock(UrgenciaDocumentos.class), mock(UrgenciaCupo.class), mock(UrgenciaAuditWriter.class), Clock.systemUTC());
    }

    private static void verificarActor(UrgenciaRevisionService service, Usuario usuario, Authentication auth) {
        ReflectionTestUtils.invokeMethod(service, "checkActor", usuario, auth);
    }

    private static void assertForbidden(org.junit.jupiter.api.function.Executable executable) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, executable);
        assertEquals(403, exception.getStatusCode().value());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class ConfiguracionAislada {
        @Bean JwtAuthenticationFilter jwtAuthenticationFilter() { return mock(JwtAuthenticationFilter.class); }
        @Bean CuentasEnMemoria userDetailsService() { return new CuentasEnMemoria(); }
        @Bean EndpointsSinDatos endpointsSinDatos() { return new EndpointsSinDatos(); }
    }

    static class CuentasEnMemoria implements UserDetailsService {
        final Map<String, UserDetails> actuales = new ConcurrentHashMap<>();
        final AtomicInteger lecturas = new AtomicInteger();
        @Override public UserDetails loadUserByUsername(String username) {
            lecturas.incrementAndGet();
            UserDetails found = actuales.get(username);
            if (found == null) throw new UsernameNotFoundException("Identidad sintetica no encontrada");
            return found;
        }
    }

    @RestController
    static class EndpointsSinDatos {
        @GetMapping({"/admin/permisos-it", "/supervisor/permisos-it", "/solicitante/permisos-it"})
        public String ok() { return "ok"; }
    }
}
