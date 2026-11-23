package com.sistema.bitacora.supervisorbitacora;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.sistema.bitacora.supervisorbitacora.BitacoraPreparacionService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Directo con mocks. No carga Spring Boot/DB/SMTP ni demuestra commit, rollback o concurrencia reales. */
class BitacoraPreparacionServiceTest {
    private static final String KEY="01234567-89ab-4cde-8fab-0123456789ab";
    private static final String EMAIL="supervisor@example.invalid";
    private Harness h;
    private BitacoraPreparacionService service;
    private Authentication auth;
    @BeforeEach void preparar() {
        h=new Harness();
        service=new BitacoraPreparacionService(h.em,Clock.fixed(Instant.parse("2026-11-20T15:10:11.123456789Z"),ZoneId.of("America/Mexico_City")));
        auth=authentication(EMAIL,"SUPERVISOR");
    }
    private static Authentication authentication(String name,String role) {
        return new UsernamePasswordAuthenticationToken(name,null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    private static MarcarListo command() { return new MarcarListo(KEY,0,"Condiciones privadas de prueba\nSegunda linea","Materiales sinteticos","Sin recursos externos pendientes",null); }
    private Resultado call(MarcarListo c) { return service.marcarListo(h.solicitudId,c,auth); }
    private void status(int expected,org.junit.jupiter.api.function.Executable work) {
        ResponseStatusException ex=assertThrows(ResponseStatusException.class,work); assertEquals(expected,ex.getStatusCode().value());
    }
    private List<Write> notices() { return h.writes.stream().filter(w->w.sql.equals(SQL_AVISO)).toList(); }
    private void noWrites() { assertTrue(h.writes.isEmpty(),"No debe intentarse ningun INSERT antes de validar"); verify(h.em,never()).flush(); }

    @Test void creaEventoYAvisosSoloParaSolicitanteYCapturistaConUtcExplicito() {
        var c=command(); Resultado r=call(c);
        assertEquals(901,r.eventoId()); assertEquals(101,r.solicitudId()); assertEquals(1,r.revision());
        assertEquals("2026-11-20T15:10:11.123456Z",r.fechaUtc()); assertFalse(r.repetida());
        assertEquals(5,h.writes.size()); Write event=h.writes.getFirst(); assertEquals(SQL_EVENTO,event.sql);
        assertEquals(42L,event.p.get("actor")); assertEquals(42L,event.p.get("responsable"));
        assertEquals(7L,event.p.get("solicitante")); assertEquals(8L,event.p.get("capturista"));
        assertEquals(0L,event.p.get("esperada")); assertEquals(1L,event.p.get("revision"));
        assertEquals(c.condicionesEspacio(),event.p.get("condiciones")); assertNull(event.p.get("observaciones"));
        assertEquals(firma(101,42,c),event.p.get("firma"));
        var date=LocalDateTime.parse("2026-11-20T15:10:11.123456"); assertEquals(date,event.p.get("fecha"));
        assertEquals(Set.of(7L,8L),notices().stream().map(w->(Long)w.p.get("destino")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2,notices().stream().filter(w->"INTERNO".equals(w.p.get("canal"))).count());
        assertEquals(2,notices().stream().filter(w->"CORREO".equals(w.p.get("canal"))).count());
        for(Write w:notices()) {
            assertEquals(901L,w.p.get("evento")); assertEquals(date,w.p.get("fecha"));
            assertFalse(w.p.get("mensaje").toString().contains(c.condicionesEspacio()));
            assertTrue(w.p.get("mensaje").toString().contains("no acredita entrega ni recepcion fisica"));
            if("INTERNO".equals(w.p.get("canal"))) {
                assertEquals("DISPONIBLE",w.p.get("estado")); assertNull(w.p.get("correo")); assertNull(w.p.get("mensajeId"));
            } else {
                assertEquals("DESHABILITADO",w.p.get("estado")); assertNotNull(w.p.get("correo"));
                assertTrue(w.p.get("mensajeId").toString().matches("[0-9a-f-]{36}"));
            }
        }
        assertEquals(2,notices().stream().filter(w->w.p.get("mensajeId")!=null).map(w->w.p.get("mensajeId")).distinct().count());
        verify(h.em).flush();
    }
    @Test void participanteDuplicadoRecibeUnSoloAvisoPorCanal() {
        h.capturista=7; call(command());
        assertEquals(3,h.writes.size()); assertEquals(2,notices().size());
        assertTrue(notices().stream().allMatch(w->Long.valueOf(7).equals(w.p.get("destino"))));
    }
    @Test void supervisorTambienCapturistaRecibeUnaCopiaPorEsaParticipacionNoUnaExtra() {
        h.capturista=42; call(command());
        assertEquals(4,notices().size());
        assertEquals(2,notices().stream().filter(w->Long.valueOf(42).equals(w.p.get("destino"))).count());
    }
    @Test void siTodosLosPapelesSonLaMismaPersonaSeDeduplicaPorId() {
        h.solicitante=42; h.capturista=42; call(command());
        assertEquals(2,notices().size()); assertTrue(notices().stream().allMatch(w->Long.valueOf(42).equals(w.p.get("destino"))));
    }
    @Test void noDeduplicaIdsDistintosPorCorreoCoincidenteEnElMock() {
        h.users.put(8L,new Person(8,"solicitante@example.invalid",1)); call(command());
        assertEquals(4,notices().size()); // La politica es ID; el esquema real tiene unicidad adicional de correo.
    }
    @Test void replayExactoNoDuplicaEventoNiAvisosNiDependeDelCorreoActual() {
        var c=command(); Resultado primero=call(c); int before=h.writes.size();
        h.replay=saved(101,42,c,0,1); h.users.put(7L,new Person(7,"",1));
        h.estado="CANCELADA"; // Resultado historico ya confirmado; no intenta una nueva operacion.
        Resultado segundo=call(c);
        assertTrue(segundo.repetida()); assertEquals(primero.eventoId(),segundo.eventoId());
        assertEquals(primero.revision(),segundo.revision()); assertEquals(primero.fechaUtc(),segundo.fechaUtc());
        assertEquals(before,h.writes.size()); verify(h.em,times(1)).flush();
    }
    @Test void claveReutilizadaConTextoDistintoSeRechaza() {
        var old=command(); h.replay=saved(101,42,old,0,1);
        var changed=new MarcarListo(KEY,0,old.condicionesEspacio()+" ",old.materialesPreparados(),old.recursosExternosPreparados(),old.observaciones());
        status(409,()->call(changed)); noWrites();
    }
    @Test void claveDeOtraReservaYOtroActorNoSeExponeNiSeReutiliza() {
        h.replay=saved(101,42,command(),0,1);
        h.solicitudId=202; h.actorInicial=43; h.asignado=43L; h.correoPrincipal="otro-supervisor@example.invalid";
        h.users.put(43L,new Person(43,h.correoPrincipal,1)); auth=authentication(h.correoPrincipal,"SUPERVISOR");
        status(409,()->call(command())); noWrites();
    }
    @Test void firmaDistingueCuentaRevisionCamposYNuloFrenteAVacio() {
        var c=command(); String f=firma(101,42,c);
        assertNotEquals(f,firma(101,43,c));
        assertNotEquals(f,firma(102,42,c));
        assertNotEquals(f,firma(101,42,new MarcarListo(KEY,1,c.condicionesEspacio(),c.materialesPreparados(),c.recursosExternosPreparados(),null)));
        assertNotEquals(f,firma(101,42,new MarcarListo(KEY,0,c.condicionesEspacio(),c.materialesPreparados(),c.recursosExternosPreparados(),"")));
    }
    @Test void revisionObsoletaNoEscribe() {
        h.ultimo=Collections.singletonList(new Object[]{2L,7L,8L,42L});
        status(409,()->call(command())); noWrites();
    }
    @Test void revisionPosteriorConservaParticipantesEIncrementaUnaUnidad() {
        h.ultimo=Collections.singletonList(new Object[]{2L,7L,8L,42L}); var c=command();
        Resultado r=call(new MarcarListo(KEY,2,c.condicionesEspacio(),c.materialesPreparados(),c.recursosExternosPreparados(),"Revision explicita"));
        assertEquals(3,r.revision()); assertEquals(2L,h.writes.getFirst().p.get("esperada"));
    }
    @Test void cambioDeParticipantesHistoricosNoSeCorrigeImplicitamente() {
        h.ultimo=Collections.singletonList(new Object[]{1L,99L,8L,42L}); var c=command();
        status(409,()->call(new MarcarListo(KEY,1,c.condicionesEspacio(),c.materialesPreparados(),c.recursosExternosPreparados(),null))); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"PENDIENTE","RECHAZADA","CANCELADA"})
    void sinReplayReservaNoAprobadaNoSePrepara(String estado) { h.estado=estado; status(409,()->call(command())); noWrites(); }
    @Test void soloResponsableActualPuedeRegistrar() { h.asignado=77L; status(403,()->call(command())); noWrites(); }
    @Test void noInventaAsignacion() { h.asignado=null; status(403,()->call(command())); noWrites(); }
    @Test void reservaNoExiste() { h.existe=false; status(404,()->call(command())); noWrites(); }
    @Test void sesionAdminSinSupervisorNoBasta() { auth=authentication(EMAIL,"ADMINISTRADOR"); status(403,()->call(command())); assertTrue(h.queries.isEmpty()); noWrites(); }
    @Test void sesionNoAutenticadaOAnonimaNoBasta() {
        status(403,()->service.marcarListo(101,command(),null));
        var anon=new AnonymousAuthenticationToken("test","anonymousUser",List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR")));
        status(403,()->service.marcarListo(101,command(),anon)); assertTrue(h.queries.isEmpty()); noWrites();
    }
    @Test void rolVigenteEnBaseEsObligatorioAunqueLaSesionLoDeclare() { h.rolVigente=false; status(403,()->call(command())); noWrites(); }
    @Test void actorInicialNoAutorizado() { h.actorInicial=0; status(403,()->call(command())); noWrites(); }
    @Test void actorDesactivadoMientrasEsperabaBloqueoNoPuedeNiHacerReplay() {
        h.users.put(42L,new Person(42,EMAIL,0)); h.replay=saved(101,42,command(),0,1);
        status(403,()->call(command())); noWrites();
    }
    static Stream<Arguments> invalidos() {
        MarcarListo c=command();
        return Stream.of(
            Arguments.of("nulo",(MarcarListo)null),
            Arguments.of("clave nula",new MarcarListo(null,0,"c","m","e",null)),
            Arguments.of("clave no canonica",new MarcarListo(KEY.toUpperCase(java.util.Locale.ROOT),0,"c","m","e",null)),
            Arguments.of("revision negativa",new MarcarListo(KEY,-1,"c","m","e",null)),
            Arguments.of("desbordamiento",new MarcarListo(KEY,Long.MAX_VALUE,"c","m","e",null)),
            Arguments.of("condiciones nulas",new MarcarListo(KEY,0,null,"m","e",null)),
            Arguments.of("materiales blancos",new MarcarListo(KEY,0,"c"," \n","e",null)),
            Arguments.of("externos blancos",new MarcarListo(KEY,0,"c","m"," ",null)),
            Arguments.of("condiciones largas",new MarcarListo(KEY,0,"x".repeat(4001),"m","e",null)),
            Arguments.of("materiales largos",new MarcarListo(KEY,0,"c","x".repeat(4001),"e",null)),
            Arguments.of("externos largos",new MarcarListo(KEY,0,"c","m","x".repeat(4001),null)),
            Arguments.of("observaciones largas",new MarcarListo(KEY,0,"c","m","e","x".repeat(4001))),
            Arguments.of("NUL",new MarcarListo(KEY,0,"c\0","m","e",null)),
            Arguments.of("surrogado aislado",new MarcarListo(KEY,0,"\uD800","m","e",null)));
    }
    @ParameterizedTest(name="{0}") @MethodSource("invalidos")
    void comandoInvalidoNoConsultaNiEscribe(String name,MarcarListo c) { status(400,()->call(c)); assertTrue(h.queries.isEmpty()); noWrites(); }
    @Test void idInvalidoNoConsulta() { status(400,()->service.marcarListo(0,command(),auth)); assertTrue(h.queries.isEmpty()); }
    @Test void admite4000PuntosUnicodeSinAlterarElTexto() {
        String text="\uD83D\uDE00".repeat(4000); var c=new MarcarListo(KEY,0,text,"m","e",""); call(c);
        assertEquals(text,h.writes.getFirst().p.get("condiciones")); assertEquals("",h.writes.getFirst().p.get("observaciones"));
    }
    static Stream<Arguments> correosInvalidos() {
        return Stream.of(Arguments.of((Object)null),Arguments.of(""),Arguments.of(" "),Arguments.of("sin-arroba"),Arguments.of("a@localhost"),Arguments.of(" a@example.invalid"),Arguments.of("a..b@example.invalid"),Arguments.of("a@-example.invalid"),Arguments.of("a@example.invalid\r\nBcc:otra@example.invalid"));
    }
    @ParameterizedTest @MethodSource("correosInvalidos")
    void correoInvalidoDeParticipanteBloqueaAntesDelEvento(String email) {
        h.users.put(7L,new Person(7,email,1)); status(409,()->call(command())); noWrites();
    }
    @Test void faltaParticipanteNoSeInventaDireccionNiSeOmiteSilenciosamente() { h.users.remove(8L); status(409,()->call(command())); noWrites(); }
    @Test void falloDeAvisoSePropagaParaRollbackDelProxyNoDemostradoConMocks() {
        h.failAtWrite=3; IllegalStateException ex=assertThrows(IllegalStateException.class,()->call(command()));
        assertEquals("fallo-simulado-aviso",ex.getMessage()); assertEquals(3,h.writes.size());
        verify(h.em,never()).flush();
        // Estas son llamadas intentadas, NO filas persistidas. Se requiere prueba real de rollback posterior.
    }
    @Test void falloDeFlushNoSeOculta() { doThrow(new IllegalStateException("flush-simulado")).when(h.em).flush(); assertThrows(IllegalStateException.class,()->call(command())); }
    @Test void soloSeIntentanInsertsEnTablasOperativasNuevasYNoMutacionesDeEntidades() {
        call(command()); assertTrue(h.writes.stream().allMatch(w->Set.of(SQL_EVENTO,SQL_AVISO).contains(w.sql)));
        assertTrue(h.queries.stream().allMatch(sql->Set.of(SQL_ACTOR,SQL_RESERVA,SQL_ASIGNACION,SQL_USUARIOS,SQL_ROL_VIGENTE,SQL_REPLAY,SQL_ULTIMO,SQL_EVENTO,SQL_EVENTO_ID,SQL_AVISO).contains(sql)));
        verify(h.em,never()).persist(any()); verify(h.em,never()).merge(any()); verify(h.em,never()).remove(any());
    }
    @Test void contratoSinIdsDeParticipantesYAnotacionesParaProxyReal() throws Exception {
        assertEquals(List.of("clave","versionEsperada","condicionesEspacio","materialesPreparados","recursosExternosPreparados","observaciones"),Arrays.stream(MarcarListo.class.getRecordComponents()).map(c->c.getName()).toList());
        var method=BitacoraPreparacionService.class.getMethod("marcarListo",long.class,MarcarListo.class,Authentication.class);
        assertEquals("hasRole('SUPERVISOR')",method.getAnnotation(PreAuthorize.class).value());
        var tx=method.getAnnotation(Transactional.class); assertEquals(Isolation.READ_COMMITTED,tx.isolation());
        assertEquals(Propagation.REQUIRES_NEW,tx.propagation()); assertTrue(Arrays.asList(tx.rollbackFor()).contains(Exception.class));
        assertEquals(Set.of(EntityManager.class,Clock.class),Arrays.stream(BitacoraPreparacionService.class.getDeclaredFields()).filter(f->!java.lang.reflect.Modifier.isStatic(f.getModifiers())).map(f->f.getType()).collect(java.util.stream.Collectors.toSet()));
    }
    private static List<Object[]> saved(long request,long actor,MarcarListo c,long expected,long revision) {
        return Collections.singletonList(new Object[]{901L,request,actor,firma(request,actor,c),expected,revision,7L,8L,actor,"2026-11-20T15:10:11.123456"});
    }
    private record Person(long id,String email,int active) {}
    private record Write(String sql,Map<String,Object> p) {}
    private static class Harness {
        final EntityManager em=mock(EntityManager.class);
        final List<String> queries=new ArrayList<>(); final List<Write> writes=new ArrayList<>();
        final Map<Long,Person> users=new LinkedHashMap<>();
        long solicitudId=101,actorInicial=42; Long asignado=42L;
        boolean existe=true,rolVigente=true; String estado="APROBADA",correoPrincipal=EMAIL;
        int failAtWrite=0;
        List<Object[]> replay=List.of(),ultimo=List.of();
        Harness() {
            users.put(7L,new Person(7,"solicitante@example.invalid",1)); users.put(8L,new Person(8,"capturista@example.invalid",1)); users.put(42L,new Person(42,EMAIL,1));
            when(em.createNativeQuery(anyString())).thenAnswer(inv -> query(inv.getArgument(0)));
        }
        private Query query(String sql) {
            queries.add(sql); Query q=mock(Query.class); Map<String,Object> p=new LinkedHashMap<>();
            when(q.setParameter(anyString(),any())).thenAnswer(inv->{ p.put(inv.getArgument(0),inv.getArgument(1)); return q; });
            when(q.getSingleResult()).thenAnswer(inv->{
                if(sql.equals(SQL_ACTOR))return correoPrincipal.equals(p.get("correo"))?actorInicial:0L;
                if(sql.equals(SQL_EVENTO_ID))return 901L;
                throw new AssertionError("getSingleResult no esperado");
            });
            when(q.getResultList()).thenAnswer(inv->rows(sql,p));
            when(q.executeUpdate()).thenAnswer(inv->{
                if(!Set.of(SQL_EVENTO,SQL_AVISO).contains(sql))throw new AssertionError("SQL de escritura no autorizado");
                writes.add(new Write(sql,new LinkedHashMap<>(p)));
                if(failAtWrite>0 && writes.size()==failAtWrite)throw new IllegalStateException("fallo-simulado-aviso");
                return 1;
            }); return q;
        }
        private List<?> rows(String sql,Map<String,Object> p) {
            if(sql.equals(SQL_RESERVA))return existe?Collections.singletonList(new Object[]{solicitudId,estado,solicitante,capturista,"BO4-SINTETICA-SIN-BASE"}):List.of();
            if(sql.equals(SQL_ASIGNACION))return asignado==null?List.of():List.of(asignado);
            if(sql.equals(SQL_USUARIOS))return users.values().stream().filter(u->u.id==((Number)p.get("actor")).longValue() || u.id==((Number)p.get("solicitante")).longValue() || u.id==((Number)p.get("capturista")).longValue()).sorted(java.util.Comparator.comparingLong(Person::id)).map(u->new Object[]{u.id,u.email,u.active}).toList();
            if(sql.equals(SQL_ROL_VIGENTE)) {
                Person u=users.get(((Number)p.get("actor")).longValue());
                return rolVigente && u!=null && u.active==1 && u.email!=null && u.email.equalsIgnoreCase(p.get("correo").toString())?List.of(u.id):List.of();
            }
            if(sql.equals(SQL_REPLAY))return replay;
            if(sql.equals(SQL_ULTIMO))return ultimo;
            throw new AssertionError("Consulta de lista no esperada");
        }
        long solicitante=7,capturista=8;
    }
}
