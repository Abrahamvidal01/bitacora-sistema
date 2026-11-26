package com.sistema.bitacora.supervisorcuentas;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.Filter;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import com.sistema.bitacora.supervisorcuentas.GestionCuentasCatalogoController.DepartamentoOpcion;

/** Isolated synthetic context, NOT the application's SecurityConfig or a real database. */
class GestionCuentasCatalogoControllerTest {
    private static final String PATH = "/supervisor/api/cuentas/departamentos";
    private AnnotationConfigWebApplicationContext context;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactions;
    private GestionCuentasCatalogoController direct;
    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebAislada.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        transactions = context.getBean(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(call -> new SimpleTransactionStatus());
        count(1L);
        rows(List.of(new DepartamentoOpcion(7L, "Archivo")));
        direct = new GestionCuentasCatalogoController(jdbc, transactions);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        org.mockito.Mockito.clearInvocations(jdbc, transactions);
    }

    @AfterEach
    void cerrar() {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void guardiaDirectaExigeAmbosRoles(int mask) {
        Authentication auth = auth(mask);
        MockHttpServletRequest request = request(auth, auth);
        if ((mask & 3) == 3) {
            assertEquals(1, direct.departamentos(request, auth).getBody().size());
        } else {
            assertEquals(403, assertThrows(ResponseStatusException.class,
                    () -> direct.departamentos(request, auth)).getStatusCode().value());
            verifyNoInteractions(jdbc, transactions);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void proxyRealDeMetodoYHttpExigenAmbosRoles(int mask) throws Exception {
        mvc.perform(get(PATH).session(session(auth(mask))))
                .andExpect(status().is((mask & 3) == 3 ? 200 : 403))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        if ((mask & 3) != 3) verifyNoInteractions(jdbc, transactions);
    }

    @Test
    void rechazaSesionAusenteIncompletaODistinta() {
        Authentication both = auth(3);
        MockHttpServletRequest missing = new MockHttpServletRequest();
        SecurityContextHolder.getContext().setAuthentication(both);
        assertDenied(missing, both);
        assertDenied(request(both, auth(1)), both);
        Authentication other = new UsernamePasswordAuthenticationToken("otro.operador", null,
                both.getAuthorities());
        assertDenied(request(both, other), both);
        MockHttpServletRequest changedCurrent = request(both, both);
        SecurityContextHolder.getContext().setAuthentication(auth(3));
        assertDenied(changedCurrent, both);
        assertDenied(request(both, both), null);
        Authentication anonymous = new AnonymousAuthenticationToken("fixture", "anonymousUser",
                both.getAuthorities());
        assertDenied(request(anonymous, anonymous), anonymous);
        Authentication unauthenticated = new UsernamePasswordAuthenticationToken(
                "operador.catalogo", null);
        assertDenied(request(unauthenticated, unauthenticated), unauthenticated);
        verifyNoInteractions(jdbc, transactions);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, 2})
    void actorInactivoAusenteSinRolesODuplicadoNoLeeDepartamentos(Long actualCount)
            throws Exception {
        count(actualCount);
        mvc.perform(get(PATH).session(session(auth(3))))
                .andExpect(status().isForbidden())
                .andExpect(content().string("{\"error\":\"Operacion no autorizada.\"}"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(jdbc, never()).query(anyString(), anyMapper());
        verify(transactions).rollback(any(TransactionStatus.class));
    }

    @Test
    void entregaSoloOpcionesPorConversionJsonNormalYMapeaColumnasExactas() throws Exception {
        when(jdbc.query(anyString(), anyMapper())).thenAnswer(call -> {
            RowMapper<DepartamentoOpcion> mapper = call.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id", Long.class)).thenReturn(7L);
            when(rs.getString("nombre_departamento")).thenReturn("Archivo");
            return List.of(mapper.mapRow(rs, 0));
        });
        mvc.perform(get(PATH).session(session(auth(3))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string("[{\"id\":7,\"nombre\":\"Archivo\"}]"))
                .andExpect(header().string("Cache-Control", "no-store"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), anyMapper());
        assertTrue(sql.getValue().contains("WHERE activo = 1"));
        assertTrue(sql.getValue().contains("ORDER BY nombre_departamento, id LIMIT 2001"));
        verify(transactions).commit(any(TransactionStatus.class));
    }

    @Test
    void consultaActorNormalizadoYRolExactoEnTransaccionNuevaSoloLectura() {
        Authentication base = auth(3);
        Authentication actor = new UsernamePasswordAuthenticationToken(" OPERADOR.CATALOGO ",
                null, base.getAuthorities());
        direct.departamentos(request(actor, actor), actor);
        ArgumentCaptor<TransactionDefinition> definition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        var ordered = inOrder(transactions, jdbc);
        ordered.verify(transactions).getTransaction(definition.capture());
        ordered.verify(jdbc).queryForObject(sql.capture(), eq(Long.class),
                eq("operador.catalogo"), eq("ADMINISTRADOR"), eq("SUPERVISOR"));
        ordered.verify(jdbc).query(anyString(), anyMapper());
        ordered.verify(transactions).commit(any(TransactionStatus.class));
        assertTrue(definition.getValue().isReadOnly());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                definition.getValue().getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                definition.getValue().getIsolationLevel());
        assertTrue(sql.getValue().contains("u.activo = 1"));
        assertTrue(sql.getValue().contains("LOWER(TRIM(u.correo_institucional)) = ?"));
        assertEquals(2, sql.getValue().split("BINARY r.nombre_rol = BINARY ", -1).length - 1);
    }

    @Test
    void parametrosDesconocidosNoLleganALaBase() throws Exception {
        mvc.perform(get(PATH).session(session(auth(3))).queryParam("extra", "ignorar"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("{\"error\":\"Solicitud invalida.\"}"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(jdbc, transactions);
    }

    static Stream<DepartamentoOpcion> datosInvalidos() {
        return Stream.of(new DepartamentoOpcion(null, "Archivo"),
                new DepartamentoOpcion(0L, "Archivo"), new DepartamentoOpcion(-1L, "Archivo"),
                new DepartamentoOpcion(1L, null), new DepartamentoOpcion(1L, " "),
                new DepartamentoOpcion(1L, "x".repeat(101)));
    }

    @ParameterizedTest
    @MethodSource("datosInvalidos")
    void datosInvalidosNoSePublican(DepartamentoOpcion row) throws Exception {
        rows(List.of(row));
        mvc.perform(get(PATH).session(session(auth(3))))
                .andExpect(status().isConflict())
                .andExpect(content().string("{\"error\":\"Catalogo no disponible.\"}"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void listaVaciaEsValidaPeroNoSeTruncaAlSuperarDosMil() throws Exception {
        Authentication actor = auth(3);
        rows(List.of());
        assertEquals(List.of(), direct.departamentos(request(actor, actor), actor).getBody());
        rows(Collections.nCopies(2000, new DepartamentoOpcion(1L, "Archivo")));
        assertEquals(2000, direct.departamentos(request(actor, actor), actor).getBody().size());
        rows(Collections.nCopies(2001, new DepartamentoOpcion(1L, "Archivo")));
        SecurityContextHolder.clearContext();
        mvc.perform(get(PATH).session(session(auth(3))))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void errorSqlEsGenericoSinTextoInterno() throws Exception {
        when(jdbc.query(anyString(), anyMapper())).thenThrow(
                new DataAccessResourceFailureException("SQL_INTERNO_DE_PRUEBA"));
        mvc.perform(get(PATH).session(session(auth(3))))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("{\"error\":\"No se pudo procesar la solicitud.\"}"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(transactions).rollback(any(TransactionStatus.class));
    }

    private void count(Long value) {
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyString(),
                eq("ADMINISTRADOR"), eq("SUPERVISOR"))).thenReturn(value);
    }
    private void rows(List<DepartamentoOpcion> value) {
        when(jdbc.query(anyString(), anyMapper())).thenReturn(value);
    }
    private static RowMapper<DepartamentoOpcion> anyMapper() { return any(); }
    private static Authentication auth(int mask) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if ((mask & 1) != 0) authorities.add(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"));
        if ((mask & 2) != 0) authorities.add(new SimpleGrantedAuthority("ROLE_SUPERVISOR"));
        if ((mask & 4) != 0) authorities.add(new SimpleGrantedAuthority("ROLE_SOLICITANTE"));
        return new UsernamePasswordAuthenticationToken("operador.catalogo", null, authorities);
    }
    private static MockHttpSession session(Authentication saved) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(saved));
        return session;
    }
    private static MockHttpServletRequest request(Authentication current, Authentication saved) {
        SecurityContextHolder.getContext().setAuthentication(current);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session(saved));
        return request;
    }
    private void assertDenied(MockHttpServletRequest request, Authentication actor) {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> direct.departamentos(request, actor)).getStatusCode());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class WebAislada {
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
        @Bean PlatformTransactionManager transactions() {
            return mock(PlatformTransactionManager.class);
        }
        @Bean GestionCuentasCatalogoController catalogo(JdbcTemplate jdbc,
                PlatformTransactionManager transactions) {
            return new GestionCuentasCatalogoController(jdbc, transactions);
        }
        @Bean SecurityFilterChain seguridadDePrueba(HttpSecurity http) throws Exception {
            // Deliberately isolates method security; does not replace production configuration.
            return http.authorizeHttpRequests(rules -> rules.anyRequest().permitAll()).build();
        }
    }
}
