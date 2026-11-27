package com.sistema.bitacora.supervisordepartamentos;

import static com.sistema.bitacora.supervisordepartamentos.DepartamentoGestionService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Pure mock tests: NOT evidence of proxy interception, DB locking or actual rollback. */
class DepartamentoGestionServiceTest {
    private static final String CORREO = "actor@example.test";
    private static final Instant NOW = Instant.parse("2026-11-27T16:00:00.123456Z");
    private JdbcTemplate jdbc;
    private DepartamentoGestionService service;
    private PreparedStatement insertStatement;

    @BeforeEach void setup() {
        jdbc = mock(JdbcTemplate.class);
        service = new DepartamentoGestionService(jdbc,
            Clock.fixed(NOW, ZoneId.of("America/Mexico_City")));
    }

    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test void constructorDoesNotQueryOrWrite() { verifyNoInteractions(jdbc); }

    @Test void annotationsDeclareBothRolesAndIndependentTransactions() throws Exception {
        Method list = DepartamentoGestionService.class.getMethod("listar", Authentication.class);
        Method create = DepartamentoGestionService.class.getMethod("alta", AltaDepartamento.class, Authentication.class);
        for (Method method : List.of(list, create)) {
            assertEquals("hasRole('ADMINISTRADOR') and hasRole('SUPERVISOR')", method.getAnnotation(PreAuthorize.class).value());
            Transactional tx = method.getAnnotation(Transactional.class);
            assertEquals(Propagation.REQUIRES_NEW, tx.propagation());
            assertEquals(30, tx.timeout());
        }
        assertTrue(list.getAnnotation(Transactional.class).readOnly());
        assertEquals(Isolation.REPEATABLE_READ, list.getAnnotation(Transactional.class).isolation());
        Transactional tx = create.getAnnotation(Transactional.class);
        assertFalse(tx.readOnly());
        assertEquals(Isolation.READ_COMMITTED, tx.isolation());
        assertArrayEquals(new Class<?>[]{Exception.class}, tx.rollbackFor());
        var constructor = DepartamentoGestionService.class.getConstructor(JdbcTemplate.class, Clock.class);
        assertEquals("relojReservas", constructor.getParameters()[1].getAnnotation(Qualifier.class).value());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void allRoleCombinationsRequireBothAndAllowAdditionalSol(int mask) throws Exception {
        boolean allowed = (mask == 3 || mask == 7);
        if (!allowed) {
            status(403, () -> service.listar(auth(mask)));
            status(403, () -> service.alta(new AltaDepartamento("Area", null), auth(mask)));
            verifyNoInteractions(jdbc);
        } else {
            allowList(); stubList(List.of());
            assertTrue(service.listar(auth(mask)).isEmpty());
            allowActor(1, List.of(ADMIN, SUPERVISOR, "SOL")); allowInsert(42L, 1);
            assertEquals(42, service.alta(new AltaDepartamento("Area", null), auth(mask)).id());
        }
    }

    @Test void nullAnonymousUnauthenticatedOrUnsafeIdentityRejectedBeforeJdbc() {
        status(403, () -> service.listar(null));
        var anonymous = new AnonymousAuthenticationToken("key", CORREO,
            List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"), new SimpleGrantedAuthority("ROLE_SUPERVISOR")));
        status(403, () -> service.listar(inContext(anonymous)));
        status(403, () -> service.listar(inContext(new UsernamePasswordAuthenticationToken(CORREO, null))));
        for (String name : List.of("", "  ", "a b@example.test", "a\nb", "a\u200Bb", "a\uD800", "x".repeat(101))) {
            Authentication actor = mock(Authentication.class);
            when(actor.isAuthenticated()).thenReturn(true);
            doReturn(auth(3).getAuthorities()).when(actor).getAuthorities();
            when(actor.getName()).thenReturn(name);
            when(actor.getPrincipal()).thenReturn(name);
            inContext(actor);
            status(403, () -> service.listar(actor));
        }
        verifyNoInteractions(jdbc);
    }

    @Test void authenticationMustBeTheExactContextObjectBeforeAnyJdbc() {
        Authentication supplied = auth(3);
        Authentication actual = auth(7);
        assertNotSame(actual, supplied);
        status(403, () -> service.listar(supplied));
        status(403, () -> service.alta(new AltaDepartamento("Area", null), supplied));
        verifyNoInteractions(jdbc);
    }

    @Test void nullOrAnonymousPrincipalAndNullAuthoritiesFailClosed() {
        var both = List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"),
                           new SimpleGrantedAuthority("ROLE_SUPERVISOR"));
        Authentication missing = inContext(new UsernamePasswordAuthenticationToken(null, null, both));
        status(403, () -> service.listar(missing));
        Authentication anonymous = inContext(new UsernamePasswordAuthenticationToken(" anonymousUser ", null, both));
        status(403, () -> service.listar(anonymous));
        Authentication actor = mock(Authentication.class);
        when(actor.isAuthenticated()).thenReturn(true);
        when(actor.getPrincipal()).thenReturn(CORREO);
        when(actor.getName()).thenReturn(CORREO);
        doReturn(null).when(actor).getAuthorities();
        inContext(actor);
        status(403, () -> service.listar(actor));
        status(403, () -> service.alta(new AltaDepartamento("Area", null), actor));
        verifyNoInteractions(jdbc);
    }

    @Test void currentDatabaseActorRequiredForList() {
        when(jdbc.queryForObject(eq(COUNT_ACTOR), eq(Long.class), eq(CORREO))).thenReturn(0L);
        status(403, () -> service.listar(auth(3)));
        verify(jdbc, never()).query(eq(LIST), ArgumentMatchers.<RowMapper<DepartamentoVista>>any());
        assertTrue(COUNT_ACTOR.contains("BINARY r.nombre_rol"));
        assertTrue(COUNT_ACTOR.contains("u.activo = 1"));
        assertTrue(COUNT_ACTOR.contains("u.correo_institucional"));
        assertFalse(COUNT_ACTOR.contains("u.email"));
    }

    @Test void inactiveOrRevokedDatabaseActorCannotWrite() throws Exception {
        allowActor(0, List.of(ADMIN, SUPERVISOR));
        status(403, () -> service.alta(new AltaDepartamento("Area", null), auth(3)));
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        verify(jdbc, never()).queryForObject(eq(DUPLICATE), eq(Long.class), anyString());
        reset(jdbc);
        allowActor(1, List.of(ADMIN, "SOL", "supervisor"));
        status(403, () -> service.alta(new AltaDepartamento("Area", null), auth(3)));
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test void missingActorCannotWrite() {
        when(jdbc.query(eq(LOCK_ACTOR), ArgumentMatchers.<RowMapper<Object>>any(), eq(CORREO))).thenReturn(List.of());
        status(403, () -> service.alta(new AltaDepartamento("Area", null), auth(3)));
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test void commandValidationBeforeDatabaseAndRedactedDiagnostic() {
        List<AltaDepartamento> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(new AltaDepartamento(null, null));
        invalid.add(new AltaDepartamento(" \u2003 ", null));
        invalid.add(new AltaDepartamento("a".repeat(101), null));
        invalid.add(new AltaDepartamento("Area", "x".repeat(21)));
        invalid.add(new AltaDepartamento("a\uD800", null));
        invalid.add(new AltaDepartamento("Area", "x\uDC00"));
        invalid.add(new AltaDepartamento("a\nb", null));
        invalid.add(new AltaDepartamento("Area", "x\u007Fy"));
        invalid.add(new AltaDepartamento("Area", " \t "));
        invalid.add(new AltaDepartamento("Area", "\n"));
        invalid.add(new AltaDepartamento("Area\u0000", null));
        for (AltaDepartamento command : invalid)
            status(400, () -> service.alta(command, auth(3)));
        assertEquals("AltaDepartamento[REDACTADO]", new AltaDepartamento("Secret", "Private").toString());
        verifyNoInteractions(jdbc);
    }

    @Test void createsUsingGeneratedKeyAndFixedDefaultsThenAuditUtc() throws Exception {
        allowActor(1, List.of(ADMIN, SUPERVISOR)); allowInsert(901L, 1);
        DepartamentoVista view = service.alta(new AltaDepartamento("  Area Norte  ", "  Oficina A / 7  "), auth(3));
        assertEquals(new DepartamentoVista(901L, "Area Norte", "Oficina A / 7", true, 1), view);
        verify(insertStatement).setString(1, "Area Norte");
        verify(insertStatement).setString(2, "Oficina A / 7");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).query(eq(LOCK_ACTOR), ArgumentMatchers.<RowMapper<Object>>any(), eq(CORREO));
        ordered.verify(jdbc).query(eq(LOCK_ROLES), ArgumentMatchers.<RowMapper<String>>any(), eq(81L));
        ordered.verify(jdbc).queryForObject(DUPLICATE, Long.class, "Area Norte");
        ordered.verify(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        ordered.verify(jdbc).update(eq(AUDIT), args.capture());
        assertArrayEquals(new Object[]{901L, 81L, "ALTA_DEPARTAMENTO", "Area Norte", "Oficina A / 7", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)}, args.getValue());
        assertEquals(LocalDateTime.of(2026, 11, 27, 16, 0, 0, 123_456_000), args.getValue()[5]);
        assertEquals("INSERT INTO departamentos (nombre_departamento, clave_departamento, activo, cupo_mensual) VALUES (?, ?, 1, 1)", INSERT);
        assertTrue(LOCK_ACTOR.contains("FOR UPDATE"));
        assertTrue(LOCK_ROLES.contains("ORDER BY"));
        assertTrue(LOCK_ROLES.contains("FOR UPDATE"));
    }

    @Test void unicodeCodepointBoundariesAndBlankOptionalKey() throws Exception {
        allowActor(1, List.of(ADMIN, SUPERVISOR)); allowInsert(42L, 1);
        String hundred = "\uD83D\uDE80".repeat(100);
        assertEquals(hundred, service.alta(new AltaDepartamento(hundred, " \u2003 "), auth(3)).nombre());
        verify(insertStatement).setString(2, null);
        assertEquals("\uD83D\uDE80".repeat(20), service.alta(new AltaDepartamento("Area", "\uD83D\uDE80".repeat(20)), auth(3)).clave());
    }

    @Test void anyExistingNameConflictsAndNeverReactivates() throws Exception {
        allowActor(1, List.of(ADMIN, SUPERVISOR));
        when(jdbc.queryForObject(eq(DUPLICATE), eq(Long.class), anyString())).thenReturn(1L);
        status(409, () -> service.alta(new AltaDepartamento("Area", null), auth(3)));
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        assertEquals("SELECT COUNT(*) FROM departamentos WHERE nombre_departamento = ?", DUPLICATE);
        // The SQL intentionally has neither an activo predicate nor application-side LOWER.
    }

    @Test void databaseConstraintFailurePropagatesWithoutMislabelingOrAudit() throws Exception {
        allowActor(1, List.of(ADMIN, SUPERVISOR));
        var failure = new DataIntegrityViolationException("simulated DB constraint");
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
            () -> service.alta(new AltaDepartamento("Area", null), auth(3))));
        verify(jdbc, never()).update(eq(AUDIT), any(Object[].class));
    }

    @Test void missingOrUnsafeGeneratedKeyAndUnexpectedCountsFail() throws Exception {
        allowActor(1, List.of(ADMIN, SUPERVISOR)); allowInsert(0L, 1);
        assertThrows(IllegalStateException.class, () -> service.alta(new AltaDepartamento("Area", null), auth(3)));
        allowInsert(new java.math.BigDecimal("1.5"), 1);
        assertThrows(IllegalStateException.class, () -> service.alta(new AltaDepartamento("Area", null), auth(3)));
        allowInsert(null, 1);
        assertThrows(IllegalStateException.class, () -> service.alta(new AltaDepartamento("Area", null), auth(3)));
        allowInsert(42L, 0);
        assertThrows(IllegalStateException.class, () -> service.alta(new AltaDepartamento("Area", null), auth(3)));
        verify(jdbc, never()).update(eq(AUDIT), any(Object[].class));
    }

    @Test void auditFailureEscapesForTransactionalProxyButDoesNotProveRollback() throws Exception {
        allowActor(1, List.of(ADMIN, SUPERVISOR)); allowInsert(42L, 1);
        var failure = new DataIntegrityViolationException("simulated audit failure");
        when(jdbc.update(eq(AUDIT), any(Object[].class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
            () -> service.alta(new AltaDepartamento("Area", null), auth(3))));
        verify(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        // No assertion of DB state: only real proxy + MariaDB integration can prove atomic rollback.
    }

    @Test void zeroAuditRowsFails() throws Exception {
        allowActor(1, List.of(ADMIN, SUPERVISOR)); allowInsert(42L, 1);
        when(jdbc.update(eq(AUDIT), any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.alta(new AltaDepartamento("Area", null), auth(3)));
    }

    @Test void listsBothStatesAndPreservesHistoricalFourRegardlessOfName() throws Exception {
        allowList();
        ResultSet active = row(department(1, "Area A", null, 1, 4));
        ResultSet inactive = row(department(2, "Area Z", "Same key", 0, 1));
        when(jdbc.query(eq(LIST), ArgumentMatchers.<RowMapper<DepartamentoVista>>any())).thenAnswer(i -> {
            RowMapper<DepartamentoVista> mapper = i.getArgument(1);
            return List.of(mapper.mapRow(active, 0), mapper.mapRow(inactive, 1));
        });
        assertEquals(List.of(new DepartamentoVista(1, "Area A", null, true, 4),
            new DepartamentoVista(2, "Area Z", "Same key", false, 1)), service.listar(auth(3)));
        assertTrue(LIST.contains("ORDER BY nombre_departamento, id LIMIT 2001"));
        assertFalse(LIST.contains("WHERE activo"));
    }

    @Test void rejectsNullHistoricalStatusInvalidQuotaIdAndText() throws Exception {
        allowList();
        List<Map<String, Object>> broken = List.of(
            department(1, "Area", null, null, 1), department(1, "Area", null, 1, 3),
            department(1, "Area", null, 1, null), department(0, "Area", null, 1, 1),
            department(1, " ", null, 1, 1), department(1, "Area", "x".repeat(21), 1, 1),
            department(1, "Area", null, 2, 1));
        for (Map<String, Object> values : broken) {
            ResultSet rs = row(values);
            doAnswer(i -> {
                RowMapper<DepartamentoVista> mapper = i.getArgument(1);
                return List.of(mapper.mapRow(rs, 0));
            }).when(jdbc).query(eq(LIST), ArgumentMatchers.<RowMapper<DepartamentoVista>>any());
            status(409, () -> service.listar(auth(3)));
        }
    }

    @Test void bound2000IsNotSilentTruncation() {
        allowList();
        stubList(IntStream.rangeClosed(1, 2000).mapToObj(i -> new DepartamentoVista(i, "Area " + i, null, true, 1)).toList());
        assertEquals(2000, service.listar(auth(3)).size());
        stubList(IntStream.rangeClosed(1, 2001).mapToObj(i -> new DepartamentoVista(i, "Area " + i, null, true, 1)).toList());
        status(409, () -> service.listar(auth(3)));
    }

    private static Authentication auth(int mask) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if ((mask & 1) != 0) authorities.add(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"));
        if ((mask & 2) != 0) authorities.add(new SimpleGrantedAuthority("ROLE_SUPERVISOR"));
        if ((mask & 4) != 0) authorities.add(new SimpleGrantedAuthority("ROLE_SOL"));
        return inContext(new UsernamePasswordAuthenticationToken("  Actor@Example.test  ", null, authorities));
    }
    private static Authentication inContext(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }
    private static void status(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }
    private void allowList() {
        when(jdbc.queryForObject(eq(COUNT_ACTOR), eq(Long.class), eq(CORREO))).thenReturn(1L);
    }
    private void stubList(List<DepartamentoVista> rows) {
        when(jdbc.query(eq(LIST), ArgumentMatchers.<RowMapper<DepartamentoVista>>any())).thenReturn(rows);
    }
    private void allowActor(int active, List<String> roles) throws Exception {
        ResultSet actor = row(new HashMap<>(Map.of("id", 81L, "correo_institucional", CORREO, "activo", active)));
        when(jdbc.query(eq(LOCK_ACTOR), ArgumentMatchers.<RowMapper<Object>>any(), eq(CORREO))).thenAnswer(i -> {
            RowMapper<?> mapper = i.getArgument(1);
            return List.of(mapper.mapRow(actor, 0));
        });
        List<ResultSet> roleRows = new ArrayList<>();
        for (String role : roles) roleRows.add(row(Map.of("nombre_rol", role)));
        when(jdbc.query(eq(LOCK_ROLES), ArgumentMatchers.<RowMapper<String>>any(), eq(81L))).thenAnswer(i -> {
            RowMapper<String> mapper = i.getArgument(1);
            List<String> currentRoles = new ArrayList<>();
            for (int index = 0; index < roleRows.size(); index++)
                currentRoles.add(mapper.mapRow(roleRows.get(index), index));
            return currentRoles;
        });
        when(jdbc.queryForObject(eq(DUPLICATE), eq(Long.class), anyString())).thenReturn(0L);
    }
    private void allowInsert(Number key, int affected) throws Exception {
        Connection connection = mock(Connection.class);
        insertStatement = mock(PreparedStatement.class);
        when(connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)).thenReturn(insertStatement);
        doAnswer(i -> {
            PreparedStatementCreator creator = i.getArgument(0);
            assertSame(insertStatement, creator.createPreparedStatement(connection));
            KeyHolder holder = i.getArgument(1);
            if (key != null) holder.getKeyList().add(Map.of("id", key));
            return affected;
        }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        when(jdbc.update(eq(AUDIT), any(Object[].class))).thenReturn(1);
    }
    private static Map<String, Object> department(long id, String name, String key, Integer active, Integer quota) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id); data.put("nombre_departamento", name); data.put("clave_departamento", key);
        data.put("activo", active); data.put("cupo_mensual", quota);
        return data;
    }
    private static ResultSet row(Map<String, Object> values) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        AtomicBoolean wasNull = new AtomicBoolean();
        when(rs.getLong(anyString())).thenAnswer(i -> {
            Number value = (Number) values.get(i.getArgument(0));
            wasNull.set(value == null); return value == null ? 0L : value.longValue();
        });
        when(rs.getInt(anyString())).thenAnswer(i -> {
            Number value = (Number) values.get(i.getArgument(0));
            wasNull.set(value == null); return value == null ? 0 : value.intValue();
        });
        when(rs.getString(anyString())).thenAnswer(i -> {
            String value = (String) values.get(i.getArgument(0));
            wasNull.set(value == null); return value;
        });
        when(rs.wasNull()).thenAnswer(i -> wasNull.get());
        return rs;
    }
}
