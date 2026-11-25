package com.sistema.bitacora.supervisorbitacora;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.time.YearMonth;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Direct unit tests only. No Spring context, server, DB, Maven invocation or SQL execution. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupervisorIndicadoresControllerTest {
    @Mock EntityManager em;
    @Mock Query actorQuery;
    @Mock Query monthQuery;
    @Mock Query usersQuery;
    @Mock PlatformTransactionManager transactions;
    SupervisorIndicadoresController controller;
    MockHttpServletRequest request;
    Authentication actor;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(new SimpleTransactionStatus());
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assertTrue(sql.startsWith("SELECT "), "Only SELECT is allowed");
            if (sql.equals(SupervisorIndicadoresController.ACTOR_SQL)) return actorQuery;
            if (sql.equals(SupervisorIndicadoresController.MONTH_SQL)) return monthQuery;
            if (sql.equals(SupervisorIndicadoresController.ACTIVE_SQL)) return usersQuery;
            throw new AssertionError("Unexpected SQL");
        });
        for (Query query : new Query[] { actorQuery, monthQuery, usersQuery }) {
            when(query.setParameter(anyString(), any())).thenReturn(query);
            when(query.setHint(anyString(), any())).thenReturn(query);
        }
        when(actorQuery.getSingleResult()).thenReturn(BigInteger.ONE);
        when(monthQuery.getSingleResult()).thenReturn(row(10, 10, 10, 2, 4, 6, 4));
        when(usersQuery.getSingleResult()).thenReturn(BigInteger.valueOf(17));
        controller = new SupervisorIndicadoresController(em, transactions);
        request = new MockHttpServletRequest();
        actor = auth("supervisor@example.invalid", "ROLE_SUPERVISOR");
        saveSession(actor);
    }

    private static Authentication auth(String name, String role) {
        return new UsernamePasswordAuthenticationToken(name, "not-a-secret",
            AuthorityUtils.createAuthorityList(role));
    }

    private void saveSession(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        request.setSession(session);
    }

    private static Object[] row(long... values) {
        Object[] result = new Object[values.length];
        for (int i = 0; i < values.length; i++) result[i] = BigInteger.valueOf(values[i]);
        return result;
    }

    private ResponseEntity<?> call(String month) {
        return controller.indicadores(month, actor, request);
    }

    private static void status(ResponseEntity<?> response, int expected) {
        assertEquals(expected, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-cache", response.getHeaders().getFirst("Pragma"));
        assertEquals("0", response.getHeaders().getFirst("Expires"));
        assertTrue(response.getHeaders().getVary().contains("Cookie"));
    }

    @Test
    void mapsFiveCountsWithoutActorIdentity() {
        ResponseEntity<?> response = call("2026-11");
        status(response, 200);
        SupervisorIndicadoresController.Indicadores body =
            (SupervisorIndicadoresController.Indicadores) response.getBody();
        assertNotNull(body);
        assertEquals("2026-11", body.mes);
        assertEquals(2, body.pendientesDictamen);
        assertEquals(4, body.aprobadasMes);
        assertEquals(6, body.urgentesMes);
        assertEquals(4, body.normalesMes);
        assertEquals(17, body.usuariosActivosActual);
        assertEquals(6, body.getClass().getFields().length);
        verify(actorQuery).setParameter("correo", actor.getName());
        verify(usersQuery).getSingleResult();
        verify(usersQuery, never()).setParameter(anyString(), any());
    }

    @Test
    void emptyMonthStillReadsCurrentGlobalActiveUsers() {
        when(monthQuery.getSingleResult()).thenReturn(row(0, 0, 0, 0, 0, 0, 0));
        ResponseEntity<?> response = call("2026-02");
        status(response, 200);
        SupervisorIndicadoresController.Indicadores body =
            (SupervisorIndicadoresController.Indicadores) response.getBody();
        assertNotNull(body);
        assertEquals(0, body.pendientesDictamen);
        assertEquals(0, body.aprobadasMes);
        assertEquals(0, body.urgentesMes);
        assertEquals(0, body.normalesMes);
        assertEquals(17, body.usuariosActivosActual);
    }

    @Test
    void unauthenticatedAndAnonymousDoNotTouchDatabase() {
        status(controller.indicadores("2026-11", null, request), 401);
        Authentication anonymous = new AnonymousAuthenticationToken("test", "anonymousUser",
            AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        status(controller.indicadores("2026-11", anonymous, request), 401);
        Authentication unauthenticated =
            new UsernamePasswordAuthenticationToken("supervisor@example.invalid", "unused");
        status(controller.indicadores("2026-11", unauthenticated, request), 401);
        verifyNoInteractions(em, transactions);
    }

    @Test
    void administratorAloneIsNotSupervisor() {
        actor = auth("admin@example.invalid", "ROLE_ADMIN");
        saveSession(actor);
        status(call("2026-11"), 403);
        verifyNoInteractions(em, transactions);
    }

    @Test
    void sessionIsMandatoryEvenWithSupervisorAuthentication() {
        request.setSession(null);
        status(call("2026-11"), 403);
        verifyNoInteractions(em, transactions);
    }

    @Test
    void savedSecurityContextMustBeSameActorAndSupervisor() {
        saveSession(auth("other@example.invalid", "ROLE_SUPERVISOR"));
        status(call("2026-11"), 403);
        saveSession(auth(actor.getName(), "ROLE_ADMIN"));
        status(call("2026-11"), 403);
        request.getSession(false).removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        status(call("2026-11"), 403);
        verifyNoInteractions(em, transactions);
    }

    @Test
    void inactiveOrRevokedDatabaseRoleIsDeniedAndNotCounted() {
        when(actorQuery.getSingleResult()).thenReturn(BigInteger.ZERO);
        status(call("2026-11"), 403);
        verify(monthQuery, never()).getSingleResult();
        verify(usersQuery, never()).getSingleResult();
        assertTrue(SupervisorIndicadoresController.ACTOR_SQL.contains("u.activo = 1"));
        assertTrue(SupervisorIndicadoresController.ACTOR_SQL.contains("BINARY r.nombre_rol = 'SUPERVISOR'"));
        assertTrue(SupervisorIndicadoresController.ACTOR_SQL.contains("EXISTS"));
    }

    @Test
    void ambiguousDatabaseAccountsAreDenied() {
        when(actorQuery.getSingleResult()).thenReturn(BigInteger.valueOf(2));
        status(call("2026-11"), 403);
        verify(monthQuery, never()).getSingleResult();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"2026-1", "2026-00", "2026-13", " 2026-11", "2026-11 ",
        "2026-11-01", "0000-01", "0999-12", "9999-01", "10000-01", "2026-+1", "abcd-11"})
    void invalidMonthIsRejectedBeforeDatabase(String value) {
        status(call(value), 400);
        verifyNoInteractions(em, transactions);
        assertThrows(IllegalArgumentException.class, () -> SupervisorIndicadoresController.parseMes(value));
    }

    @Test
    void parserIsIndependentAndAcceptsOnlySupportedBounds() {
        assertEquals(YearMonth.of(1000, 1), SupervisorIndicadoresController.parseMes("1000-01"));
        assertEquals(YearMonth.of(9998, 12), SupervisorIndicadoresController.parseMes("9998-12"));
        assertEquals(YearMonth.of(2024, 2), SupervisorIndicadoresController.parseMes("2024-02"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000-01", "2024-02", "2026-12", "9998-12"})
    void queriesHalfOpenEventMonthAcrossYearAndLeapBoundaries(String value) {
        status(call(value), 200);
        YearMonth month = YearMonth.parse(value);
        verify(monthQuery).setParameter("inicio", Date.valueOf(month.atDay(1)));
        verify(monthQuery).setParameter("fin", Date.valueOf(month.plusMonths(1).atDay(1)));
        assertTrue(SupervisorIndicadoresController.MONTH_SQL.contains("s.fecha_evento >= :inicio"));
        assertTrue(SupervisorIndicadoresController.MONTH_SQL.contains("s.fecha_evento < :fin"));
    }

    @Test
    void missingOrMalformedModalityAndUnknownStateAreUnavailable() {
        when(monthQuery.getSingleResult()).thenReturn(row(10, 9, 10, 2, 4, 6, 4));
        status(call("2026-11"), 409);
        when(monthQuery.getSingleResult()).thenReturn(row(10, 10, 9, 2, 4, 6, 4));
        status(call("2026-11"), 409);
        verify(usersQuery, never()).getSingleResult();
    }

    @Test
    void inconsistentTotalsAndBrokenRowsAreUnavailable() {
        when(monthQuery.getSingleResult()).thenReturn(row(10, 10, 10, 7, 4, 6, 4));
        status(call("2026-11"), 409);
        when(monthQuery.getSingleResult()).thenReturn(row(10, 10, 10, 2, 11, 6, 4));
        status(call("2026-11"), 409);
        when(monthQuery.getSingleResult()).thenReturn(row(10, 10, 10, 2, 4, 6, 3));
        status(call("2026-11"), 409);
        when(monthQuery.getSingleResult()).thenReturn(new Object[] { 1L });
        status(call("2026-11"), 409);
        when(monthQuery.getSingleResult()).thenReturn(null);
        status(call("2026-11"), 409);
    }

    @Test
    void safeNumbersAreExactAndInvalidNumbersAreNotSilentlyTruncated() {
        assertEquals(0, SupervisorIndicadoresController.safeCount(BigInteger.ZERO));
        assertEquals(12, SupervisorIndicadoresController.safeCount(new BigDecimal("12.000")));
        assertEquals(9007199254740991L,
            SupervisorIndicadoresController.safeCount(new BigInteger("9007199254740991")));
        for (Object invalid : new Object[] { null, "1", -1L, new BigDecimal("0.5"),
            new BigInteger("9007199254740992"), new BigInteger("9223372036854775808"),
            Double.NaN, Double.POSITIVE_INFINITY }) {
            assertThrows(SupervisorIndicadoresController.DatosNoDisponibles.class,
                () -> SupervisorIndicadoresController.safeCount(invalid));
        }
        when(usersQuery.getSingleResult()).thenReturn(new BigDecimal("1.1"));
        status(call("2026-11"), 409);
        Object[] broken = row(10, 10, 10, 2, 4, 6, 4);
        broken[4] = null;
        when(monthQuery.getSingleResult()).thenReturn(broken);
        status(call("2026-11"), 409);
    }

    @Test
    void allDatabaseFailuresReturnGenericNoStoreResponse() {
        when(monthQuery.getSingleResult()).thenThrow(new IllegalStateException("PRIVATE SQL DETAILS"));
        ResponseEntity<?> response = call("2026-11");
        status(response, 503);
        assertTrue(response.getBody() instanceof Map);
        assertFalse(response.getBody().toString().contains("PRIVATE"));
        assertFalse(response.getBody().toString().contains(actor.getName()));
    }

    @Test
    void transactionCompletionFailureIsAlsoGeneric() {
        doThrow(new IllegalStateException("PRIVATE COMMIT DETAILS")).when(transactions).commit(any());
        ResponseEntity<?> response = call("2026-11");
        status(response, 503);
        assertFalse(response.getBody().toString().contains("PRIVATE"));
    }

    @Test
    void methodSecurityExceptionUsesStructuredNoStoreBody() {
        ResponseEntity<?> response = controller.accessDenied(new AccessDeniedException("PRIVATE"));
        status(response, 403);
        assertFalse(response.getBody().toString().contains("PRIVATE"));
    }

    @Test
    void transactionIsReadOnlyNewRepeatableReadWithThirtySecondTimeout() {
        status(call("2026-11"), 200);
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertTrue(definition.getValue().isReadOnly());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            definition.getValue().getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,
            definition.getValue().getIsolationLevel());
        assertEquals(30, definition.getValue().getTimeout());
        verify(actorQuery).setHint("jakarta.persistence.query.timeout", 30000);
        verify(monthQuery).setHint("jakarta.persistence.query.timeout", 30000);
        verify(usersQuery).setHint("jakarta.persistence.query.timeout", 30000);
        for (Query query : new Query[] { actorQuery, monthQuery, usersQuery }) {
            verify(query, never()).executeUpdate();
        }
        verify(em, never()).persist(any());
        verify(em, never()).merge(any());
        verify(em, never()).remove(any());
        verify(em, never()).flush();
    }

    @Test
    void sqlRetainsExplicitModalitiesRecognizedStatesAndIndependentUserCount() {
        String sql = SupervisorIndicadoresController.MONTH_SQL;
        assertTrue(sql.contains("LEFT JOIN confirmaciones_reserva c ON c.solicitud_id = s.id"));
        assertTrue(sql.contains("BINARY s.estatus_solicitud = 'PENDIENTE' " +
            "AND BINARY c.modalidad = 'URGENTE_REQUISICION'"));
        assertTrue(sql.contains("BINARY s.estatus_solicitud = 'APROBADA' THEN 1"));
        assertTrue(sql.contains("'APROBADA','PENDIENTE','RECHAZADA','CANCELADA'"));
        assertTrue(sql.contains("'URGENTE_INVENTARIO','URGENTE_REQUISICION'"));
        assertFalse(sql.contains("usuario_roles"));
        assertFalse(sql.contains("nombramientos"));
        assertEquals("SELECT COUNT(*) FROM usuarios WHERE activo = 1",
            SupervisorIndicadoresController.ACTIVE_SQL);
    }
}
