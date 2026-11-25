package com.sistema.bitacora.supervisorcuentas;

import com.sistema.bitacora.entity.Departamento;
import com.sistema.bitacora.entity.Rol;
import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.supervisorcuentas.GestionCuentasAltaService.AltaCommand;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated unit tests only: no Spring proxy, transaction, HTTP, DB or application bootstrap. */
@ExtendWith(MockitoExtension.class)
class GestionCuentasAltaServiceTest {
    private static final String ACTOR = "gestor@example.org";
    private static final String NOMBRE = "Persona de prueba";
    private static final String CORREO = "nueva@example.org";
    private static final String PASSWORD = "  ClaveSegura123  ";
    private static final String HASH = "hash-simulado-no-es-credencial-real";
    @Mock private EntityManager em;
    @Mock private PasswordEncoder encoder;
    @Mock private Query actorQuery;
    @Mock private Query actorRolesQuery;
    @Mock private Query historialQuery;
    @Mock private TypedQuery<Rol> rolesQuery;
    @Mock private TypedQuery<Long> duplicadosQuery;
    @Mock private Departamento departamento;
    @Mock private Rol administrador;
    @Mock private Rol supervisor;
    @Mock private Rol solicitante;
    private final Clock reloj = Clock.fixed(Instant.parse("2026-11-25T18:00:00Z"), ZoneOffset.UTC);
    private GestionCuentasAltaService service;
    private Authentication authentication;

    @BeforeEach void preparar() {
        SecurityContextHolder.clearContext();
        service = new GestionCuentasAltaService(em, encoder, reloj);
        authentication = autenticar(ACTOR, "ADMINISTRADOR", "SUPERVISOR");
        // Lenient defaults let rejected-input tests prove zero DB calls.
        lenient().when(em.createNativeQuery(argThat(sql -> sql != null
                && sql.contains("FROM usuarios WHERE")))).thenReturn(actorQuery);
        lenient().when(em.createNativeQuery(argThat(sql -> sql != null
                && sql.contains("FROM usuario_roles")))).thenReturn(actorRolesQuery);
        lenient().when(em.createNativeQuery(argThat(sql -> sql != null
                && sql.contains("INSERT INTO historial_usuarios")))).thenReturn(historialQuery);
        lenient().when(actorQuery.setParameter(anyString(), any())).thenReturn(actorQuery);
        lenient().when(actorRolesQuery.setParameter(anyString(), any())).thenReturn(actorRolesQuery);
        lenient().when(historialQuery.setParameter(anyString(), any())).thenReturn(historialQuery);
        lenient().when(actorQuery.getResultList()).thenReturn(
                Collections.singletonList(new Object[] { 41L, ACTOR, Boolean.TRUE }));
        lenient().when(actorRolesQuery.getResultList()).thenReturn(
                List.of("ADMINISTRADOR", "SUPERVISOR"));
        lenient().when(em.find(eq(Departamento.class), eq(7L),
                eq(LockModeType.PESSIMISTIC_WRITE), anyMap())).thenReturn(departamento);
        lenient().when(departamento.getActivo()).thenReturn(Boolean.TRUE);
        lenient().when(administrador.getNombreRol()).thenReturn("ADMINISTRADOR");
        lenient().when(supervisor.getNombreRol()).thenReturn("SUPERVISOR");
        lenient().when(solicitante.getNombreRol()).thenReturn("SOLICITANTE");
        lenient().when(em.createQuery(anyString(), eq(Rol.class))).thenReturn(rolesQuery);
        lenient().when(rolesQuery.setParameter(anyString(), any())).thenReturn(rolesQuery);
        lenient().when(rolesQuery.setLockMode(any())).thenReturn(rolesQuery);
        lenient().when(rolesQuery.setHint(anyString(), any())).thenReturn(rolesQuery);
        lenient().when(rolesQuery.getResultList()).thenReturn(List.of(solicitante));
        lenient().when(em.createQuery(anyString(), eq(Long.class))).thenReturn(duplicadosQuery);
        lenient().when(duplicadosQuery.setParameter(anyString(), any())).thenReturn(duplicadosQuery);
        lenient().when(duplicadosQuery.getSingleResult()).thenReturn(0L);
        lenient().when(encoder.encode(any(CharSequence.class))).thenReturn(HASH);
        lenient().doAnswer(invocation -> {
            Usuario nuevo = invocation.getArgument(0);
            nuevo.setId(91L);
            return null;
        }).when(em).persist(any(Usuario.class));
        lenient().when(historialQuery.executeUpdate()).thenReturn(1);
    }
    @AfterEach void limpiar() { SecurityContextHolder.clearContext(); }

    private Authentication autenticar(String correo, String... nombres) {
        var authorities = Arrays.stream(nombres)
                .map(rol -> new SimpleGrantedAuthority("ROLE_" + rol)).toList();
        Authentication token = new UsernamePasswordAuthenticationToken(correo, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
        return token;
    }
    private static AltaCommand comando() {
        return new AltaCommand(NOMBRE, CORREO, PASSWORD, 7L, Set.of("SOLICITANTE"));
    }
    private static AltaCommand conPassword(String password) {
        return new AltaCommand(NOMBRE, CORREO, password, 7L, Set.of("SOLICITANTE"));
    }
    private static void estado(HttpStatus esperado, Runnable accion) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, accion::run);
        assertEquals(esperado, ex.getStatusCode());
        assertFalse(ex.getMessage().contains(PASSWORD));
        assertFalse(ex.getMessage().contains(CORREO));
        assertFalse(ex.getMessage().contains(NOMBRE));
    }

    @Test void contratoDeSeguridadYTransaccionExplicito() throws Exception {
        Method method = GestionCuentasAltaService.class.getMethod(
                "alta", AltaCommand.class, Authentication.class);
        assertEquals("hasRole('ADMINISTRADOR') and hasRole('SUPERVISOR')",
                method.getAnnotation(PreAuthorize.class).value());
        Transactional tx = method.getAnnotation(Transactional.class);
        assertNotNull(tx);
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation());
        assertEquals(Isolation.READ_COMMITTED, tx.isolation());
        assertEquals(30, tx.timeout());
        assertArrayEquals(new Class<?>[] { Exception.class }, tx.rollbackFor());
        assertFalse(tx.readOnly());
        assertArrayEquals(new String[] { "nombreCompleto", "correoInstitucional", "password",
                "departamentoId", "roles" }, Arrays.stream(AltaCommand.class.getRecordComponents())
                .map(component -> component.getName()).toArray(String[]::new));
        assertArrayEquals(new String[] { "usuarioId" }, Arrays.stream(
                GestionCuentasAltaService.AltaResultado.class.getRecordComponents())
                .map(component -> component.getName()).toArray(String[]::new));
    }
    @Test void normalizaSinTocarPasswordYDevuelveSoloId() {
        AltaCommand command = new AltaCommand("  " + NOMBRE + "  ",
                "  NUEVA@EXAMPLE.ORG  ", PASSWORD, 7L, Set.of("SOLICITANTE"));
        assertEquals(91L, service.alta(command, authentication).usuarioId());
        ArgumentCaptor<Usuario> captor = ArgumentCaptor.forClass(Usuario.class);
        verify(em).persist(captor.capture());
        Usuario nuevo = captor.getValue();
        assertEquals(NOMBRE, nuevo.getNombreCompleto());
        assertEquals(CORREO, nuevo.getCorreoInstitucional());
        assertEquals(HASH, nuevo.getPasswordHash());
        assertSame(departamento, nuevo.getDepartamento());
        assertEquals(Set.of(solicitante), nuevo.getRoles());
        assertTrue(nuevo.getActivo());
        verify(encoder).encode(PASSWORD);
        verify(em, times(2)).flush();
        verify(duplicadosQuery).setParameter("correo", CORREO);
    }
    @Test void flushDeUsuarioLuegoInsertAuditoriaLuegoSegundoFlush() {
        service.alta(comando(), authentication);
        InOrder orden = inOrder(em, historialQuery);
        orden.verify(em).persist(any(Usuario.class));
        orden.verify(em).flush();
        orden.verify(historialQuery).executeUpdate();
        orden.verify(em).flush();
        verify(em, times(2)).flush();
    }
    @Test void falloSegundoFlushSePropagaSinReintentar() {
        doNothing().doThrow(new PersistenceException("Fallo de sincronizacion simulado"))
                .when(em).flush();
        assertThrows(PersistenceException.class, () -> service.alta(comando(), authentication));
        verify(em, times(2)).flush();
        verify(historialQuery, times(1)).executeUpdate();
        verify(em, times(1)).persist(any(Usuario.class));
        // Actual rollback requires a real Spring transaction/DB integration test.
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void aceptaUnoDosOTresRolesIncluidosOtrosGestores(int cantidad) {
        List<Rol> lista = List.of(administrador, supervisor, solicitante).subList(0, cantidad);
        Set<String> nombres = new LinkedHashSet<>();
        for (Rol rol : lista) { nombres.add(rol.getNombreRol()); }
        when(rolesQuery.getResultList()).thenReturn(lista);
        assertEquals(91L, service.alta(new AltaCommand(NOMBRE, CORREO, PASSWORD, 7L, nombres),
                authentication).usuarioId());
        ArgumentCaptor<Usuario> nuevo = ArgumentCaptor.forClass(Usuario.class);
        verify(em).persist(nuevo.capture());
        assertEquals(new LinkedHashSet<>(lista), nuevo.getValue().getRoles());
    }
    @Test void permiteSolicitanteAdicionalEnSesionYEnBaseActual() {
        authentication = autenticar(ACTOR, "ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE");
        when(actorRolesQuery.getResultList()).thenReturn(
                List.of("ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE"));
        assertEquals(91L, service.alta(comando(), authentication).usuarioId());
    }
    @ParameterizedTest @ValueSource(strings = {"otra.persona@example.org", "otra.cuenta@gmail.com"})
    void noExisteListaFijaDePersonasNiDominioUniversitario(String correoActor) {
        authentication = autenticar(correoActor, "ADMINISTRADOR", "SUPERVISOR");
        when(actorQuery.getResultList()).thenReturn(
                Collections.singletonList(new Object[] { 55L, correoActor, 1 }));
        service.alta(comando(), authentication);
        verify(actorQuery).setParameter("correo", correoActor);
        verify(historialQuery).setParameter("actor", 55L);
    }
    @Test void actorYRolesActualesSeBloqueanAntesDeDepartamentoYRolesAsignados() {
        service.alta(comando(), authentication);
        InOrder orden = inOrder(actorQuery, actorRolesQuery, em, rolesQuery, duplicadosQuery);
        orden.verify(actorQuery).getResultList();
        orden.verify(actorRolesQuery).getResultList();
        orden.verify(em).find(eq(Departamento.class), eq(7L),
                eq(LockModeType.PESSIMISTIC_WRITE), anyMap());
        orden.verify(rolesQuery).setLockMode(LockModeType.PESSIMISTIC_READ);
        orden.verify(rolesQuery).getResultList();
        orden.verify(duplicadosQuery).getSingleResult();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, times(3)).createNativeQuery(sql.capture());
        List<String> consultas = sql.getAllValues();
        assertTrue(consultas.get(0).contains("FOR UPDATE"));
        assertTrue(consultas.get(0).contains("correo_institucional = :correo"));
        assertTrue(consultas.get(1).contains("ORDER BY r.id FOR UPDATE"));
        assertTrue(consultas.get(1).contains("ur.usuario_id = :actor"));
    }
    @Test void auditoriaIncluyeSoloIdsRolesOrdenadosYFechaExplicita() {
        when(rolesQuery.getResultList()).thenReturn(List.of(supervisor, administrador, solicitante));
        service.alta(new AltaCommand(NOMBRE, CORREO, PASSWORD, 7L,
                Set.of("SUPERVISOR", "SOLICITANTE", "ADMINISTRADOR")), authentication);
        verify(historialQuery).setParameter("afectado", 91L);
        verify(historialQuery).setParameter("actor", 41L);
        verify(historialQuery).setParameter("accion", "ALTA_CUENTA");
        verify(historialQuery).setParameter("detalle",
                "roles=ADMINISTRADOR,SOLICITANTE,SUPERVISOR; departamentoId=7");
        verify(historialQuery).setParameter("fecha",
                Timestamp.valueOf(LocalDateTime.of(2026, 11, 25, 12, 0)));
        verify(historialQuery).executeUpdate();
    }
    @ParameterizedTest @ValueSource(strings = {"ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE"})
    void unSoloRolDeSesionNoAutoriza(String rol) {
        authentication = autenticar(ACTOR, rol);
        estado(HttpStatus.FORBIDDEN, () -> service.alta(comando(), authentication));
        verifyNoInteractions(em, encoder);
    }
    @Test void sinAutoridadesNoAutoriza() {
        authentication = autenticar(ACTOR);
        estado(HttpStatus.FORBIDDEN, () -> service.alta(comando(), authentication));
        verifyNoInteractions(em, encoder);
    }
    @Test void rechazaAuthNulaAnonimaNoAutenticadaYPrincipalVacio() {
        estado(HttpStatus.FORBIDDEN, () -> service.alta(comando(), null));
        Authentication anonimo = new AnonymousAuthenticationToken("clave-prueba", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anonimo);
        estado(HttpStatus.FORBIDDEN, () -> service.alta(comando(), anonimo));
        Authentication noAutenticado = new UsernamePasswordAuthenticationToken(ACTOR, null);
        SecurityContextHolder.getContext().setAuthentication(noAutenticado);
        estado(HttpStatus.FORBIDDEN, () -> service.alta(comando(), noAutenticado));
        Authentication vacio = autenticar(" ", "ADMINISTRADOR", "SUPERVISOR");
        estado(HttpStatus.FORBIDDEN, () -> service.alta(comando(), vacio));
        verifyNoInteractions(em, encoder);
    }
    @Test void rechazaAuthDistintaDeContextoActualAunqueRepitaAutoridades() {
        Authentication otra = new UsernamePasswordAuthenticationToken(ACTOR, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"),
                        new SimpleGrantedAuthority("ROLE_SUPERVISOR")));
        estado(HttpStatus.FORBIDDEN, () -> service.alta(comando(), otra));
        SecurityContextHolder.clearContext();
        estado(HttpStatus.FORBIDDEN, () -> service.alta(comando(), authentication));
        verifyNoInteractions(em, encoder);
    }
    @Test void rechazaActorInexistenteOInactivo() {
        when(actorQuery.getResultList()).thenReturn(List.of());
        estado(HttpStatus.FORBIDDEN, () -> service.alta(comando(), authentication));
        when(actorQuery.getResultList()).thenReturn(
                Collections.singletonList(new Object[] {41L, ACTOR, false}));
        estado(HttpStatus.FORBIDDEN, () -> service.alta(comando(), authentication));
        verifyNoInteractions(actorRolesQuery, encoder);
        verify(em, never()).persist(any());
    }
    static Stream<Arguments> rolesActualesInsuficientes() {
        return Stream.of(Arguments.of(List.of()), Arguments.of(List.of("ADMINISTRADOR")),
                Arguments.of(List.of("SUPERVISOR")),
                Arguments.of(List.of("ADMINISTRADOR", "SOLICITANTE")),
                Arguments.of(List.of("SUPERVISOR", "SOLICITANTE")));
    }
    @ParameterizedTest @MethodSource("rolesActualesInsuficientes")
    void sesionAntiguaNoSustituyeRolesActuales(List<String> roles) {
        when(actorRolesQuery.getResultList()).thenReturn(roles);
        estado(HttpStatus.FORBIDDEN, () -> service.alta(comando(), authentication));
        verify(em, never()).find(eq(Departamento.class), any(), any(), anyMap());
        verify(em, never()).persist(any());
        verifyNoInteractions(encoder);
    }
    static Stream<Arguments> entradasInvalidas() {
        List<AltaCommand> entradas = new ArrayList<>();
        entradas.add(null);
        entradas.add(new AltaCommand(null, CORREO, PASSWORD, 7L, Set.of("SOLICITANTE")));
        entradas.add(new AltaCommand("   ", CORREO, PASSWORD, 7L, Set.of("SOLICITANTE")));
        entradas.add(new AltaCommand("a".repeat(151), CORREO, PASSWORD, 7L, Set.of("SOLICITANTE")));
        entradas.add(new AltaCommand("Nombre" + (char) 10, CORREO, PASSWORD, 7L, Set.of("SOLICITANTE")));
        entradas.add(new AltaCommand(String.valueOf((char) 0xD800), CORREO, PASSWORD, 7L,
                Set.of("SOLICITANTE")));
        entradas.add(new AltaCommand(NOMBRE, null, PASSWORD, 7L, Set.of("SOLICITANTE")));
        for (String correo : List.of("sin-arroba", "x@localhost", "x@@example.org",
                "x..y@example.org", "x@-example.org", "x@example..org", "x".repeat(101) + "@x.org")) {
            entradas.add(new AltaCommand(NOMBRE, correo, PASSWORD, 7L, Set.of("SOLICITANTE")));
        }
        entradas.add(new AltaCommand(NOMBRE, CORREO, null, 7L, Set.of("SOLICITANTE")));
        for (String password : List.of("", "x".repeat(11), "x".repeat(73),
                String.valueOf((char) 0xF1).repeat(37),
                Character.toString(0x1F512).repeat(6),
                "PasswordSeguro123" + (char) 0,
                "PasswordSeguro123" + (char) 10,
                "PasswordSeguro123" + (char) 13,
                "PasswordSeguro123" + (char) 0x200B,
                "PasswordSeguro123" + (char) 0xD800)) {
            entradas.add(conPassword(password));
        }
        entradas.add(new AltaCommand(NOMBRE, CORREO, PASSWORD, null, Set.of("SOLICITANTE")));
        entradas.add(new AltaCommand(NOMBRE, CORREO, PASSWORD, 0L, Set.of("SOLICITANTE")));
        entradas.add(new AltaCommand(NOMBRE, CORREO, PASSWORD, -1L, Set.of("SOLICITANTE")));
        entradas.add(new AltaCommand(NOMBRE, CORREO, PASSWORD, 7L, null));
        entradas.add(new AltaCommand(NOMBRE, CORREO, PASSWORD, 7L, Set.of()));
        entradas.add(new AltaCommand(NOMBRE, CORREO, PASSWORD, 7L,
                new LinkedHashSet<>(Arrays.asList("SOLICITANTE", null))));
        for (Set<String> roles : List.of(Set.of("ADMIN"), Set.of("supervisor"),
                Set.of("ROLE_ADMINISTRADOR"), Set.of(" SUPERVISOR"),
                Set.of("ADMINISTRADOR", "SUPERVISOR", "SOLICITANTE", "OTRO"))) {
            entradas.add(new AltaCommand(NOMBRE, CORREO, PASSWORD, 7L, roles));
        }
        return entradas.stream().map(entrada -> Arguments.of(entrada));
    }
    @ParameterizedTest(name = "payload invalido {index}") @MethodSource("entradasInvalidas")
    void invalidoDa400SinBaseNiEncoder(AltaCommand entrada) {
        estado(HttpStatus.BAD_REQUEST, () -> service.alta(entrada, authentication));
        verifyNoInteractions(em, encoder);
    }
    @Test void constructorEsDefensivoRedactadoYNulosNoLanzanNpe() {
        Set<String> entrada = new LinkedHashSet<>(Set.of("SOLICITANTE"));
        AltaCommand command = new AltaCommand(NOMBRE, CORREO, PASSWORD, 7L, entrada);
        entrada.clear();
        assertEquals(Set.of("SOLICITANTE"), command.roles());
        assertThrows(UnsupportedOperationException.class, () -> command.roles().add("SUPERVISOR"));
        assertEquals("AltaCommand[contenido=REDACTADO]", command.toString());
        assertDoesNotThrow(() -> new AltaCommand(null, null, null, null, null));
        assertDoesNotThrow(() -> new AltaCommand(NOMBRE, CORREO, PASSWORD, 7L,
                new LinkedHashSet<>(Arrays.asList("SOLICITANTE", null))));
    }
    @ParameterizedTest @ValueSource(ints = {12, 72})
    void aceptaLimitesAsciiDePasswordSinRecortar(int longitud) {
        service.alta(conPassword("x".repeat(longitud)), authentication);
        verify(encoder).encode("x".repeat(longitud));
    }
    @Test void hasta72BytesUnicodeSeAceptanConBcryptReal() {
        String password = Character.toString(0x1F512).repeat(18); // 18 CP, 72 UTF-8 bytes.
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4); // Test cost only.
        service = new GestionCuentasAltaService(em, bcrypt, reloj);
        service.alta(conPassword(password), authentication);
        ArgumentCaptor<Usuario> nuevo = ArgumentCaptor.forClass(Usuario.class);
        verify(em).persist(nuevo.capture());
        assertTrue(bcrypt.matches(password, nuevo.getValue().getPasswordHash()));
        assertNotEquals(password, nuevo.getValue().getPasswordHash());
    }
    @Test void nombrePuedeTener150Codepoints() {
        service.alta(new AltaCommand("x".repeat(150), CORREO, PASSWORD, 7L,
                Set.of("SOLICITANTE")), authentication);
        verify(em).persist(any(Usuario.class));
    }
    @Test void departamentoInexistenteOInactivoNoCreaNada() {
        when(em.find(eq(Departamento.class), eq(7L),
                eq(LockModeType.PESSIMISTIC_WRITE), anyMap())).thenReturn(null);
        estado(HttpStatus.BAD_REQUEST, () -> service.alta(comando(), authentication));
        when(em.find(eq(Departamento.class), eq(7L),
                eq(LockModeType.PESSIMISTIC_WRITE), anyMap())).thenReturn(departamento);
        when(departamento.getActivo()).thenReturn(false);
        estado(HttpStatus.BAD_REQUEST, () -> service.alta(comando(), authentication));
        verifyNoInteractions(encoder, historialQuery);
        verify(em, never()).persist(any());
    }
    @Test void noCreaRolesFaltantesNiAceptaResultadosInesperados() {
        when(rolesQuery.getResultList()).thenReturn(List.of());
        estado(HttpStatus.CONFLICT, () -> service.alta(comando(), authentication));
        when(rolesQuery.getResultList()).thenReturn(List.of(administrador));
        estado(HttpStatus.CONFLICT, () -> service.alta(comando(), authentication));
        when(rolesQuery.getResultList()).thenReturn(List.of(solicitante, solicitante));
        estado(HttpStatus.CONFLICT, () -> service.alta(comando(), authentication));
        when(rolesQuery.getResultList()).thenReturn(Collections.singletonList(null));
        estado(HttpStatus.CONFLICT, () -> service.alta(comando(), authentication));
        verify(em, never()).persist(any());
        verifyNoInteractions(encoder);
    }
    @Test void correoDuplicadoSeRechazaAntesDeEscribir() {
        when(duplicadosQuery.getSingleResult()).thenReturn(1L);
        estado(HttpStatus.CONFLICT, () -> service.alta(comando(), authentication));
        verifyNoInteractions(encoder, historialQuery);
        verify(em, never()).persist(any());
    }
    @Test void falloUniqueEnPrimerFlushSePropagaSinReintentosNiAuditoria() {
        doThrow(new PersistenceException("Fallo DB simulado")).when(em).flush();
        assertThrows(PersistenceException.class, () -> service.alta(comando(), authentication));
        verify(em, times(1)).persist(any(Usuario.class));
        verify(em, times(1)).flush();
        verifyNoInteractions(historialQuery);
    }
    @Test void falloAuditoriaSePropagaParaRollbackDelProxy() {
        when(historialQuery.executeUpdate()).thenThrow(new PersistenceException("Fallo simulado"));
        assertThrows(PersistenceException.class, () -> service.alta(comando(), authentication));
        verify(em, times(1)).persist(any(Usuario.class));
        verify(em, times(1)).flush();
        verify(historialQuery, times(1)).executeUpdate();
        // Actual rollback is deliberately NOT asserted by mocks.
    }
    @Test void historialSinFilaNoInformaExito() {
        when(historialQuery.executeUpdate()).thenReturn(0);
        estado(HttpStatus.INTERNAL_SERVER_ERROR, () -> service.alta(comando(), authentication));
        verify(em, times(1)).flush();
    }
    @Test void idNoGeneradoNoInformaExito() {
        doNothing().when(em).persist(any(Usuario.class));
        estado(HttpStatus.INTERNAL_SERVER_ERROR, () -> service.alta(comando(), authentication));
        verifyNoInteractions(historialQuery);
    }
    @Test void encoderSinResultadoNoPersiste() {
        when(encoder.encode(any(CharSequence.class))).thenReturn(null);
        estado(HttpStatus.INTERNAL_SERVER_ERROR, () -> service.alta(comando(), authentication));
        verify(em, never()).persist(any());
    }
}
