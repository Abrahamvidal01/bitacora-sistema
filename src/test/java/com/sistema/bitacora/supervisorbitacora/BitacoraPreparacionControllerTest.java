package com.sistema.bitacora.supervisorbitacora;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.sistema.bitacora.supervisorbitacora.BitacoraPreparacionController.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unidad directa con mocks: NO valida HTTP real, filtros, CSRF ni transacciones MariaDB. */
class BitacoraPreparacionControllerTest {
    private Harness h;
    private BitacoraPreparacionService service;
    private BitacoraPreparacionController controller;
    private Authentication auth;
    @BeforeEach void setup() {
        h=new Harness(); service=mock(BitacoraPreparacionService.class);
        controller=new BitacoraPreparacionController(h.em,service); auth=authentication("SUPERVISOR");
    }
    private static Authentication authentication(String role) {
        return new UsernamePasswordAuthenticationToken("supervisor@example.invalid",null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    private static Map<String,Object> command() {
        Map<String,Object> b=new HashMap<>();
        b.put("clave","01234567-89ab-4cde-8fab-0123456789ab"); b.put("versionEsperada",0L);
        b.put("condicionesEspacio","Condiciones verificadas\nLinea dos"); b.put("materialesPreparados","Materiales sinteticos");
        b.put("recursosExternosPreparados","No aplica en el caso sintetico"); b.put("observaciones",null); return b;
    }
    private static void status(int code,org.junit.jupiter.api.function.Executable task) {
        assertEquals(code,assertThrows(ResponseStatusException.class,task).getStatusCode().value());
    }
    @Test void contextoInicialMapeaSolicitudEInventarioHistoricoSinRutasPrivadas() {
        var response=controller.contexto(101,auth); var c=response.getBody(); assertNotNull(c);
        assertEquals("no-store",response.getHeaders().getCacheControl()); assertEquals(101,c.solicitudId()); assertEquals("BO4-SINTETICA",c.folio());
        assertEquals("APROBADA",c.estado()); assertEquals("2026-12-03",c.fecha()); assertEquals("10:00",c.inicio()); assertEquals("12:00",c.fin());
        assertEquals("Sala sintetica",c.espacio()); assertEquals("Evento sintetico",c.motivoEvento()); assertEquals("Proveedor externo\nTexto original",c.recursosExternosSolicitados());
        assertTrue(c.editable()); assertEquals(0,c.versionEsperada()); assertNull(c.ultima()); assertEquals(1,c.recursos().size());
        var r=c.recursos().getFirst(); assertEquals(71,r.inventarioId()); assertEquals("Silla",r.nombre()); assertEquals("REGULAR",r.estado()); assertEquals(2,r.cantidad());
        assertThrows(UnsupportedOperationException.class,()->c.recursos().clear());
        assertEquals(201,h.limits.get(SQL_RECURSOS).intValue()); assertEquals(1,h.limits.get(SQL_ULTIMA).intValue());
        for(var call:h.calls) if(!call.sql.equals(SQL_ACTOR)) {
            assertEquals(101L,call.params.get("id")); assertEquals(42L,call.params.get("actor")); assertEquals(auth.getName(),call.params.get("correo"));
        }
        verifyNoInteractions(service); h.noWrites();
    }
    @Test void ultimaPreparacionDeterminaVersionEsperadaYFechaUtcSinSimularHistorialCompleto() {
        h.ultima=Collections.singletonList(new Object[]{901L,3L,"Condiciones","Materiales","Externos",null,"2026-11-20T15:10:11.123456"});
        var c=controller.contexto(101,auth).getBody(); assertNotNull(c); assertEquals(3,c.versionEsperada()); assertNotNull(c.ultima());
        assertEquals(901,c.ultima().eventoId()); assertEquals(3,c.ultima().revision()); assertEquals("2026-11-20T15:10:11.123456Z",c.ultima().fechaUtc());
        assertEquals("Condiciones",c.ultima().condicionesEspacio()); assertEquals("Materiales",c.ultima().materialesPreparados()); assertEquals("Externos",c.ultima().recursosExternosPreparados()); assertNull(c.ultima().observaciones()); h.noWrites();
    }
    @Test void contextoNoAprobadoSoloPuedeLeersePorResponsableSinHabilitarFormulario() {
        h.root=Collections.singletonList(new Object[]{101L,"BO4-SINTETICA","CANCELADA","2026-12-03","10:00","12:00","Sala","Evento",null});
        var c=controller.contexto(101,auth).getBody(); assertNotNull(c); assertFalse(c.editable()); assertEquals("",c.recursosExternosSolicitados()); h.noWrites();
    }
    @Test void idDeOtroResponsableONoExistenteNoDevuelveProyecciones() {
        // El mock representa el WHERE de asignacion que no encuentra la cuenta actual.
        h.root=List.of(); status(404,()->controller.contexto(999,auth));
        assertEquals(List.of(SQL_ACTOR,SQL_CONTEXTO),h.calls.stream().map(x->x.sql).toList());
        verifyNoInteractions(service); h.noWrites();
    }
    @Test void bajaDeRolOCuentaInactivaImpideConsultarContexto() {
        h.actor=0; status(403,()->controller.contexto(101,auth)); assertEquals(1,h.calls.size()); assertEquals(SQL_ACTOR,h.calls.getFirst().sql); h.noWrites();
    }
    @Test void revocacionObservadaAlFinalImpideDevolverDetalleYaConsultado() { h.acceso=0; status(404,()->controller.contexto(101,auth)); h.noWrites(); }
    @Test void adminSoloNoBastaAunqueElMockDbTengaActor() { status(403,()->controller.contexto(101,authentication("ADMINISTRADOR"))); assertTrue(h.calls.isEmpty()); h.noWrites(); }
    @Test void sesionNulaOAnonimaNoConsulta() {
        status(403,()->controller.contexto(101,null));
        var anon=new AnonymousAuthenticationToken("test","anonymousUser",List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR")));
        status(403,()->controller.contexto(101,anon)); assertTrue(h.calls.isEmpty());
    }
    @Test void idInvalidoSeRechazaAntesDeProyectar() { status(400,()->controller.contexto(0,auth)); assertEquals(1,h.calls.size()); }
    @Test void masDe200RecursosNoSeTruncan() {
        h.recursos=new ArrayList<>(); for(int i=0;i<201;i++)h.recursos.add(new Object[]{(long)i+1,"Articulo","BUENO",1});
        status(409,()->controller.contexto(101,auth)); assertTrue(h.calls.stream().noneMatch(x->x.sql.equals(SQL_ULTIMA))); h.noWrites();
    }
    @Test void proyeccionInconsistenteNoSeExponeComoResultadoExitoso() {
        h.root=Collections.singletonList(new Object[]{202L,"OTRA","APROBADA","2026-12-03","10:00","12:00","Sala","Evento",""});
        assertThrows(IllegalStateException.class,()->controller.contexto(101,auth)); h.noWrites();
    }
    @Test void csrfRevalidaActorActualYNoDevuelveTokenSiNoHayPermiso() {
        var token=new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","solo-token-sintetico");
        var r=controller.csrf(auth,token); assertEquals("no-store",r.getHeaders().getCacheControl()); assertNotNull(r.getBody());
        assertEquals("X-CSRF-TOKEN",r.getBody().headerName()); assertEquals("solo-token-sintetico",r.getBody().token());
        h.actor=0; status(403,()->controller.csrf(auth,token)); h.noWrites();
    }
    @Test void csrfAusenteNoSeInventa() { assertThrows(IllegalStateException.class,()->controller.csrf(auth,null)); }
    @Test void postDelegaTextoExactoYLaAutenticacionRecibidaSinIdsDeCliente() {
        var b=command(); var result=new BitacoraPreparacionService.Resultado(901,101,1,"2026-11-20T15:10:11.123456Z",false);
        when(service.marcarListo(eq(101L),any(),same(auth))).thenReturn(result);
        var response=controller.listo(101,b,auth); assertSame(result,response.getBody()); assertEquals("no-store",response.getHeaders().getCacheControl());
        var captor=org.mockito.ArgumentCaptor.forClass(BitacoraPreparacionService.MarcarListo.class);
        verify(service).marcarListo(eq(101L),captor.capture(),same(auth)); var c=captor.getValue();
        assertEquals(b.get("clave"),c.clave()); assertEquals(0,c.versionEsperada()); assertEquals(b.get("condicionesEspacio"),c.condicionesEspacio());
        assertEquals(b.get("materialesPreparados"),c.materialesPreparados()); assertEquals(b.get("recursosExternosPreparados"),c.recursosExternosPreparados()); assertNull(c.observaciones());
        assertTrue(h.calls.isEmpty(),"El servicio real hace su propia validacion DB y transaccion; el controller no escribe"); h.noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"actor","actorUsuarioId","supervisorId","destinatarios","solicitanteId","capturistaId","otraPropiedad"})
    void propiedadesExtraIncluyendoIdsSeRechazan(String extra) {
        var b=command(); b.put(extra,42L); status(400,()->controller.listo(101,b,auth)); verifyNoInteractions(service); h.noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"clave","versionEsperada","condicionesEspacio","materialesPreparados","recursosExternosPreparados","observaciones"})
    void losSeisCamposSonExplicitosAunqueObservacionesAdmitaNulo(String missing) {
        var b=command(); b.remove(missing); status(400,()->controller.listo(101,b,auth)); verifyNoInteractions(service);
    }
    static Stream<Object> versionesInvalidas() { return Stream.of(1.5D,0.0D,"0",Boolean.TRUE,new BigInteger("9223372036854775808")); }
    @ParameterizedTest @MethodSource("versionesInvalidas")
    void versionNoEnteraOFueraDelTipoLongSeRechaza(Object value) {
        var b=command(); b.put("versionEsperada",value); status(400,()->controller.listo(101,b,auth)); verifyNoInteractions(service);
    }
    @Test void versionBigIntegerRepresentableSeConvierteSinPerdida() {
        var b=command(); b.put("versionEsperada",BigInteger.valueOf(4)); controller.listo(101,b,auth);
        verify(service).marcarListo(eq(101L),argThat(c->c.versionEsperada()==4),same(auth));
    }
    @Test void camposConTiposDistintosNoSeTransformanEnTexto() {
        var b=command(); b.put("materialesPreparados",List.of("no")); status(400,()->controller.listo(101,b,auth));
        var other=command(); other.put("observaciones",42);
        status(400,()->controller.listo(101,other,auth)); verifyNoInteractions(service);
    }
    @Test void bodyNuloYVersionNulaNoDelegan() {
        status(400,()->controller.listo(101,null,auth)); var b=command(); b.put("versionEsperada",null);
        status(400,()->controller.listo(101,b,auth)); verifyNoInteractions(service);
    }
    @Test void postSesionAdminSinSupervisorNoDelega() { status(403,()->controller.listo(101,command(),authentication("ADMINISTRADOR"))); verifyNoInteractions(service); }
    @Test void excepcionDelServicioSePropagaHastaManejadorSinConvertirlaEn200() {
        when(service.marcarListo(anyLong(),any(),any())).thenThrow(new IllegalStateException("detalle-privado-no-publicar"));
        Exception ex=assertThrows(IllegalStateException.class,()->controller.listo(101,command(),auth));
        var r=controller.incierto(ex); assertEquals(503,r.getStatusCode().value()); assertEquals("no-store",r.getHeaders().getCacheControl());
        assertNotNull(r.getBody()); assertTrue(r.getBody().resultadoIncierto()); assertFalse(r.getBody().mensaje().contains("detalle-privado"));
    }
    @Test void manejadoresDefinitivosNoRevelanExcepcionesDeInfraestructura() {
        var negocio=controller.negocio(new ResponseStatusException(HttpStatus.CONFLICT,"Revision obsoleta"));
        assertEquals(409,negocio.getStatusCode().value()); assertNotNull(negocio.getBody()); assertFalse(negocio.getBody().resultadoIncierto());
        var denegado=controller.denegado(new AccessDeniedException("secreto-sintetico")); assertEquals(403,denegado.getStatusCode().value()); assertNotNull(denegado.getBody()); assertFalse(denegado.getBody().mensaje().contains("secreto"));
        var entrada=controller.entrada(new IllegalArgumentException("detalle-no-publicar")); assertEquals(400,entrada.getStatusCode().value()); assertNotNull(entrada.getBody()); assertFalse(entrada.getBody().resultadoIncierto());
    }
    @Test void ambitoSqlIncluyeAsignacionActorActivoYRolEnTodasLasProyecciones() {
        for(String sql:List.of(SQL_CONTEXTO,SQL_RECURSOS,SQL_ULTIMA,SQL_ACCESO)) {
            assertTrue(sql.contains("a.supervisor_usuario_id=:actor")); assertTrue(sql.contains("u.activo=1"));
            assertTrue(sql.contains("u.correo_institucional=:correo")); assertTrue(sql.contains("r.nombre_rol='SUPERVISOR'")); assertTrue(sql.contains("s.id=:id"));
        }
        assertTrue(SQL_RECURSOS.contains("FROM solicitud_recursos")); assertFalse(SQL_RECURSOS.contains("FROM inventario_espacios"));
    }
    @Test void anotacionesDeclaranSoloSupervisorYLecturasSinEscritura() throws Exception {
        assertEquals("hasRole('SUPERVISOR')",BitacoraPreparacionController.class.getAnnotation(PreAuthorize.class).value());
        var method=BitacoraPreparacionController.class.getMethod("contexto",long.class,Authentication.class);
        var tx=method.getAnnotation(Transactional.class); assertTrue(tx.readOnly()); assertEquals(Isolation.READ_COMMITTED,tx.isolation());
        assertNull(BitacoraPreparacionController.class.getMethod("listo",long.class,Map.class,Authentication.class).getAnnotation(Transactional.class));
        Set<String> fields=java.util.Arrays.stream(Contexto.class.getRecordComponents()).map(c->c.getName()).collect(java.util.stream.Collectors.toSet());
        assertFalse(fields.stream().anyMatch(s->s.toLowerCase(java.util.Locale.ROOT).contains("correo") || s.toLowerCase(java.util.Locale.ROOT).contains("ruta")));
    }
    private record Call(String sql,Map<String,Object> params) {}
    private static class Harness {
        final EntityManager em=mock(EntityManager.class); final List<Call> calls=new ArrayList<>(); final List<Query> queries=new ArrayList<>();
        final Map<String,Integer> limits=new HashMap<>(); long actor=42,acceso=1;
        List<Object[]> root=Collections.singletonList(new Object[]{101L,"BO4-SINTETICA","APROBADA","2026-12-03","10:00","12:00","Sala sintetica","Evento sintetico","Proveedor externo\nTexto original"});
        List<Object[]> recursos=Collections.singletonList(new Object[]{71L,"Silla","REGULAR",2}); List<Object[]> ultima=List.of();
        Harness() { when(em.createNativeQuery(anyString())).thenAnswer(inv->query(inv.getArgument(0))); }
        private Query query(String sql) {
            Query q=mock(Query.class); queries.add(q); Map<String,Object> p=new LinkedHashMap<>(); calls.add(new Call(sql,p));
            when(q.setParameter(anyString(),any())).thenAnswer(inv->{p.put(inv.getArgument(0),inv.getArgument(1));return q;});
            when(q.setMaxResults(anyInt())).thenAnswer(inv->{limits.put(sql,inv.getArgument(0));return q;});
            when(q.getSingleResult()).thenAnswer(inv->{if(sql.equals(SQL_ACTOR))return actor;if(sql.equals(SQL_ACCESO))return acceso;throw new AssertionError("SQL escalar inesperado");});
            when(q.getResultList()).thenAnswer(inv->{if(sql.equals(SQL_CONTEXTO))return root;if(sql.equals(SQL_RECURSOS))return recursos;if(sql.equals(SQL_ULTIMA))return ultima;throw new AssertionError("SQL lista inesperado");});
            when(q.executeUpdate()).thenThrow(new AssertionError("GET no debe ejecutar DML")); return q;
        }
        void noWrites() { for(Query q:queries)verify(q,never()).executeUpdate(); verify(em,never()).flush(); verify(em,never()).persist(any()); verify(em,never()).merge(any()); verify(em,never()).remove(any()); }
    }
}
