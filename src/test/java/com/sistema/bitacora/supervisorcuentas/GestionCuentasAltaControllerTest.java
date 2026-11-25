package com.sistema.bitacora.supervisorcuentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Static proposal, not executed here. Isolated MVC/security chain only.
 * Does NOT import the real SecurityConfig, log in, start Boot or connect to a DB.
 * CSRF and authorization behavior here is not integration evidence for production.
 */
class GestionCuentasAltaControllerTest {
    private static final String PATH = "/supervisor/api/cuentas";
    private static final String PASSWORD = "  ClaveLarga123!  ";
    private AnnotationConfigWebApplicationContext context;
    private GestionCuentasAltaService service;
    private MockMvc mvc;

    @BeforeEach
    void prepararCadenaAislada() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SoloMvcSeguridad.class);
        context.refresh();
        // Method-security may proxy the mock; stub/verify the underlying mock only.
        service = AopTestUtils.getUltimateTargetObject(
                context.getBean(GestionCuentasAltaService.class));
        when(service.alta(any(), any()))
                .thenReturn(new GestionCuentasAltaService.AltaResultado(23L));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cerrar() {
        SecurityContextHolder.clearContext();
        if (context != null) {
            context.close();
        }
    }

    record RoleCase(List<String> roles, boolean accepted) { }

    static Stream<RoleCase> roleCases() {
        return Stream.of(
                new RoleCase(List.of("ADMINISTRADOR"), false),
                new RoleCase(List.of("SUPERVISOR"), false),
                new RoleCase(List.of("SOLICITANTE"), false),
                new RoleCase(List.of("ADMINISTRADOR", "SUPERVISOR"), true),
                new RoleCase(List.of("ADMINISTRADOR", "SOLICITANTE"), false),
                new RoleCase(List.of("SUPERVISOR", "SOLICITANTE"), false),
                new RoleCase(List.of("ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE"), true));
    }

    @ParameterizedTest
    @MethodSource("roleCases")
    void sieteCombinacionesSoloPermitenAmbosRoles(RoleCase item) throws Exception {
        Authentication auth = auth("actor@example.test", item.roles().toArray(String[]::new));
        MvcResult result = mvc.perform(valid().session(session(auth)).with(csrf()))
                .andExpect(status().is(item.accepted() ? 201 : 403)).andReturn();
        if (item.accepted()) {
            assertEquals("{\"usuarioId\":23}", result.getResponse().getContentAsString());
            verify(service).alta(any(), same(auth));
        } else {
            verifyNoInteractions(service);
        }
    }

    @Test
    void anonimoNoInvocaServicio() throws Exception {
        mvc.perform(valid().with(csrf())).andExpect(status().is3xxRedirection());
        verifyNoInteractions(service);
    }

    @Test
    void sinRolesNoInvocaServicio() throws Exception {
        mvc.perform(valid().session(session(auth("actor@example.test"))).with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void csrfAusenteConSesionValidaEs403() throws Exception {
        mvc.perform(valid().session(privilegedSession())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void csrfInvalidoConSesionValidaEs403() throws Exception {
        mvc.perform(valid().session(privilegedSession()).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void csrfValidoEntregaSoloIdSinNormalizarPassword() throws Exception {
        Authentication auth = auth("actor@example.test", "ADMINISTRADOR", "SUPERVISOR");
        mvc.perform(valid().session(session(auth)).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"usuarioId\":23}"));
        ArgumentCaptor<GestionCuentasAltaService.AltaCommand> command =
                ArgumentCaptor.forClass(GestionCuentasAltaService.AltaCommand.class);
        verify(service, times(1)).alta(command.capture(), same(auth));
        assertEquals("Nombre de prueba", command.getValue().nombreCompleto());
        assertEquals("nuevo@example.test", command.getValue().correoInstitucional());
        assertEquals(PASSWORD, command.getValue().password());
        assertEquals(1L, command.getValue().departamentoId());
        assertEquals(Set.of("SOLICITANTE"), command.getValue().roles());
        verifyNoMoreInteractions(service);
    }

    @Test
    void csrfEnCabeceraTambienFunciona() throws Exception {
        mvc.perform(valid().session(privilegedSession()).with(csrf().asHeader()))
                .andExpect(status().isCreated());
        verify(service, times(1)).alta(any(), any());
    }

    @Test
    void queryNoVaciaSeRechazaSinEco() throws Exception {
        invalid(valid().queryParam("password", "SECRET_MARKER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"activo", "actor", "actorId", "passwordHash", "hash", "otro"})
    void camposDesconocidosSon400(String key) throws Exception {
        invalid(valid().param(key, "SECRET_MARKER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nombreCompleto", "correoInstitucional", "password", "departamentoId"})
    void escalaresRepetidosSon400(String key) throws Exception {
        invalid(valid().param(key, "SECRET_MARKER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nombreCompleto", "correoInstitucional", "password", "departamentoId", "roles"})
    void camposAusentesSon400(String key) throws Exception {
        invalid(validWithout(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nombreCompleto", "correoInstitucional", "password", "departamentoId"})
    void escalaresVaciosSon400(String key) throws Exception {
        invalid(validWithout(key).param(key, ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMINISTRADOR,ADMINISTRADOR", "SUPERVISOR,SUPERVISOR",
            "ADMINISTRADOR,SUPERVISOR,SOLICITANTE,ADMINISTRADOR", "ROLE_SUPERVISOR",
            "supervisor", " SUPERVISOR", "OTRO", ""})
    void rolesRepetidosFueraDeListaOExcesivosSon400(String roles) throws Exception {
        invalid(validWithout("roles").param("roles", roles.split(",", -1)));
    }

    @Test
    void tresRolesUnicosSeConservan() throws Exception {
        mvc.perform(validWithout("roles").param("roles", "ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE")
                .session(privilegedSession()).with(csrf())).andExpect(status().isCreated());
        ArgumentCaptor<GestionCuentasAltaService.AltaCommand> command =
                ArgumentCaptor.forClass(GestionCuentasAltaService.AltaCommand.class);
        verify(service).alta(command.capture(), any());
        assertEquals(Set.of("ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE"), command.getValue().roles());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "+1", "01", "1.0", "1e3", " 1", "1 ",
            "9223372036854775808", "999999999999999999999", "\u0661"})
    void departamentoExigeLongPositivoCanonico(String id) throws Exception {
        invalid(validWithout("departamentoId").param("departamentoId", id));
    }

    @Test
    void longMaximoEsSintacticamenteValidoPeroServicioDecideExistencia() throws Exception {
        mvc.perform(validWithout("departamentoId").param("departamentoId", "9223372036854775807")
                .session(privilegedSession()).with(csrf())).andExpect(status().isCreated());
        ArgumentCaptor<GestionCuentasAltaService.AltaCommand> command =
                ArgumentCaptor.forClass(GestionCuentasAltaService.AltaCommand.class);
        verify(service).alta(command.capture(), any());
        assertEquals(Long.MAX_VALUE, command.getValue().departamentoId());
    }

    @Test
    void jsonEs415InclusoConSesionYCsrfValidos() throws Exception {
        mvc.perform(post(PATH).session(privilegedSession()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"SECRET_MARKER\"}"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }

    @Test
    void getNoPublicaListado() throws Exception {
        mvc.perform(get(PATH).session(privilegedSession())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }

    @Test
    void errorDeValidacionDelServicioNoFiltraRazonNiCausa() throws Exception {
        when(service.alta(any(), any())).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "SECRET_MARKER", new IllegalStateException("SECRET_MARKER")));
        safeServiceFailure(400, "{\"error\":\"Solicitud invalida.\"}");
    }

    @Test
    void actorRechazadoPorServicioNoFiltraRazon() throws Exception {
        when(service.alta(any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "SECRET_MARKER"));
        safeServiceFailure(403, "{\"error\":\"Operacion no autorizada.\"}");
    }

    @Test
    void integridadNoAfirmaCorreoDuplicado() throws Exception {
        when(service.alta(any(), any())).thenThrow(new DataIntegrityViolationException("SECRET_MARKER"));
        safeServiceFailure(409, "{\"error\":\"No se pudo completar el alta.\"}");
    }

    @Test
    void conflictoDelServicioEsGenerico() throws Exception {
        when(service.alta(any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "SECRET_MARKER"));
        safeServiceFailure(409, "{\"error\":\"No se pudo completar el alta.\"}");
    }

    @Test
    void falloInesperadoEs500GenericoSinReintentoAutomatico() throws Exception {
        when(service.alta(any(), any())).thenThrow(new IllegalStateException("SECRET_MARKER"));
        safeServiceFailure(500, "{\"error\":\"No se pudo procesar la solicitud.\"}");
    }

    @Test
    void guardiaSinFiltrosRechazaAutenticacionSinSesionYNoCreaUna() {
        Authentication auth = auth("actor@example.test", "ADMINISTRADOR", "SUPERVISOR");
        SecurityContextHolder.getContext().setAuthentication(auth);
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThrows(AccessDeniedException.class,
                () -> new GestionCuentasAltaController(service).alta(request, auth));
        assertNull(request.getSession(false));
        verifyNoInteractions(service);
    }

    @Test
    void guardiaSinFiltrosRechazaSesionSinContextoOtroActorORolesInsuficientes() {
        Authentication auth = auth("actor@example.test", "ADMINISTRADOR", "SUPERVISOR");
        SecurityContextHolder.getContext().setAuthentication(auth);
        for (MockHttpSession candidate : List.of(new MockHttpSession(),
                session(auth("otro@example.test", "ADMINISTRADOR", "SUPERVISOR")),
                session(auth("actor@example.test", "SUPERVISOR")))) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setSession(candidate);
            assertThrows(AccessDeniedException.class,
                    () -> new GestionCuentasAltaController(service).alta(request, auth));
        }
        verifyNoInteractions(service);
    }

    @Test
    void guardiaSinFiltrosExigeIdentidadConContextoActualYNoAnonimo() {
        Authentication auth = auth("actor@example.test", "ADMINISTRADOR", "SUPERVISOR");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session(auth));
        SecurityContextHolder.getContext().setAuthentication(
                auth("actor@example.test", "ADMINISTRADOR", "SUPERVISOR"));
        assertThrows(AccessDeniedException.class,
                () -> new GestionCuentasAltaController(service).alta(request, auth));
        Authentication anonymous = new AnonymousAuthenticationToken("test", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"),
                        new SimpleGrantedAuthority("ROLE_SUPERVISOR")));
        SecurityContextHolder.getContext().setAuthentication(anonymous);
        request.setSession(session(anonymous));
        assertThrows(AccessDeniedException.class,
                () -> new GestionCuentasAltaController(service).alta(request, anonymous));
        verifyNoInteractions(service);
    }

    private void invalid(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.session(privilegedSession()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string("{\"error\":\"Solicitud invalida.\"}"));
        verifyNoInteractions(service);
    }

    private void safeServiceFailure(int status, String body) throws Exception {
        mvc.perform(valid().session(privilegedSession()).with(csrf()))
                .andExpect(status().is(status))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(body));
        verify(service, times(1)).alta(any(), any());
        verifyNoMoreInteractions(service);
    }

    private MockHttpSession privilegedSession() {
        return session(auth("actor@example.test", "ADMINISTRADOR", "SUPERVISOR"));
    }

    private MockHttpSession session(Authentication auth) {
        MockHttpSession session = new MockHttpSession(context.getServletContext());
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        return session;
    }

    private static Authentication auth(String name, String... roles) {
        var authorities = Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        var principal = new User(name, "test-only-not-a-login", authorities);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    private static MockHttpServletRequestBuilder valid() {
        return validWithout("");
    }

    private static MockHttpServletRequestBuilder validWithout(String excluded) {
        var request = post(PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (!"nombreCompleto".equals(excluded)) request.param("nombreCompleto", "Nombre de prueba");
        if (!"correoInstitucional".equals(excluded)) request.param("correoInstitucional", "nuevo@example.test");
        if (!"password".equals(excluded)) request.param("password", PASSWORD);
        if (!"departamentoId".equals(excluded)) request.param("departamentoId", "1");
        if (!"roles".equals(excluded)) request.param("roles", "SOLICITANTE");
        return request;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class SoloMvcSeguridad {
        @Bean
        GestionCuentasAltaService service() {
            return mock(GestionCuentasAltaService.class);
        }

        @Bean
        GestionCuentasAltaController controller(GestionCuentasAltaService service) {
            return new GestionCuentasAltaController(service);
        }

        @Bean
        SecurityFilterChain webAislada(HttpSecurity http) throws Exception {
            // Analogous route guard, NOT the real SecurityConfig or ValidacionSesionFilter.
            // Default CSRF stays enabled; no API/JWT chain, login processing or DB here.
            http.securityMatcher("/supervisor/**")
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                    .authorizeHttpRequests(auth -> auth.requestMatchers("/supervisor/**")
                            .hasRole("SUPERVISOR").anyRequest().authenticated())
                    .formLogin(form -> form.loginPage("/login"));
            return http.build();
        }
    }
}
