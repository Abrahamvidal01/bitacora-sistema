package com.sistema.bitacora.supervisorbitacora;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Solo unitarias. NO verifican concurrencia real, transacciones, CSRF ni permisos HTTP. */
class BitacoraAsignacionServiceTest {
    private static final String CLAVE="b3253152-5954-4d3c-9334-1da93006f30e";
    private EntityManager em;
    private Query actor, replay, reserva, asignacion, insert;
    private Authentication autenticacion;
    private BitacoraAsignacionService service;
    @BeforeEach void preparar() {
        em=mock(EntityManager.class);
        actor=mock(Query.class,RETURNS_SELF);replay=mock(Query.class,RETURNS_SELF);
        reserva=mock(Query.class,RETURNS_SELF);asignacion=mock(Query.class,RETURNS_SELF);
        insert=mock(Query.class,RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenAnswer(call->{
            String sql=call.getArgument(0);
            if(sql.contains("COALESCE(MAX(u.id),0)"))return actor;
            if(sql.contains("INSERT INTO bitacora_operativa_asignaciones"))return insert;
            if(sql.contains("WHERE clave_operacion=:clave"))return replay;
            if(sql.contains("FOR UPDATE"))return reserva;
            if(sql.contains("SELECT supervisor_usuario_id"))return asignacion;
            throw new AssertionError("Consulta no contemplada en esta unidad");
        });
        when(actor.getSingleResult()).thenReturn(7L);
        when(replay.getResultList()).thenReturn(List.of());
        when(reserva.getResultList()).thenReturn(Collections.singletonList(new Object[]{4L,"APROBADA"}));
        when(asignacion.getResultList()).thenReturn(List.of());
        when(insert.executeUpdate()).thenReturn(1);
        autenticacion=new UsernamePasswordAuthenticationToken("supervisor@example.invalid","",
            List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR")));
        service=new BitacoraAsignacionService(em,Clock.fixed(Instant.parse("2026-11-19T19:00:00Z"),ZoneId.of("America/Mexico_City")));
    }
    private BitacoraAsignacionService.Tomar comando(){return new BitacoraAsignacionService.Tomar(CLAVE);}
    private void codigo(int esperado,Runnable accion){
        assertEquals(esperado,assertThrows(ResponseStatusException.class,accion::run).getStatusCode().value());
        verify(insert,never()).executeUpdate();
    }
    @Test void sinAutenticacionNoConsulta(){codigo(403,()->service.tomar(4,comando(),null));verifyNoInteractions(em);}
    @Test void claveInvalidaNoConsulta(){codigo(400,()->service.tomar(4,new BitacoraAsignacionService.Tomar("no-uuid"),autenticacion));verifyNoInteractions(em);}
    @Test void cuentaSinRolActualNoToma(){when(actor.getSingleResult()).thenReturn(0L);codigo(403,()->service.tomar(4,comando(),autenticacion));verifyNoInteractions(reserva);}
    @Test void asignaActorDeLaSesionYHoraUtcSinCambiarReserva(){
        var r=service.tomar(4,comando(),autenticacion);
        assertEquals(4,r.solicitudId());assertEquals(7,r.supervisorId());assertFalse(r.repetida());
        assertEquals("2026-11-19T19:00Z",r.fechaUtc());
        verify(insert).setParameter("actor",7L);
        verify(insert).setParameter("fecha",LocalDateTime.parse("2026-11-19T19:00:00"));
        verify(insert,times(1)).executeUpdate();verify(em).flush();
        verify(reserva,never()).executeUpdate();verify(asignacion,never()).executeUpdate();
    }
    @Test void replayExactoDevuelveOriginalSinBloquearNiInsertar(){
        when(replay.getResultList()).thenReturn(Collections.singletonList(new Object[]{4L,7L,
            BitacoraAsignacionService.firma(4,7),"2026-11-19T19:00:00.000000"}));
        var r=service.tomar(4,comando(),autenticacion);
        assertTrue(r.repetida());assertEquals(7,r.supervisorId());verifyNoInteractions(reserva);
        verify(insert,never()).executeUpdate();verify(em,never()).flush();
    }
    @Test void uuidDeOtraReservaSeRechaza(){
        when(replay.getResultList()).thenReturn(Collections.singletonList(new Object[]{9L,7L,
            BitacoraAsignacionService.firma(9,7),"2026-11-19T19:00:00.000000"}));
        codigo(409,()->service.tomar(4,comando(),autenticacion));
    }
    @Test void uuidDeOtraCuentaSeRechaza(){
        when(replay.getResultList()).thenReturn(Collections.singletonList(new Object[]{4L,8L,
            BitacoraAsignacionService.firma(4,8),"2026-11-19T19:00:00.000000"}));
        codigo(409,()->service.tomar(4,comando(),autenticacion));
    }
    @Test void otroResponsableNoSeSobrescribe(){
        when(asignacion.getResultList()).thenReturn(List.of(8L));codigo(409,()->service.tomar(4,comando(),autenticacion));
        verify(asignacion,never()).executeUpdate();
    }
    @Test void mismoResponsableConNuevaClaveNoCreaOtraAsignacion(){
        when(asignacion.getResultList()).thenReturn(List.of(7L));codigo(409,()->service.tomar(4,comando(),autenticacion));
    }
    @Test void reservaPendienteNoPuedeTomarse(){
        when(reserva.getResultList()).thenReturn(Collections.singletonList(new Object[]{4L,"PENDIENTE"}));
        codigo(409,()->service.tomar(4,comando(),autenticacion));verifyNoInteractions(asignacion);
    }
    @Test void reservaInexistenteNoSeCrea(){
        when(reserva.getResultList()).thenReturn(List.of());codigo(404,()->service.tomar(4,comando(),autenticacion));
    }
    @Test void releeUuidDespuesDeAdquirirBloqueo(){
        when(replay.getResultList()).thenReturn(List.of(),Collections.singletonList(new Object[]{4L,7L,
            BitacoraAsignacionService.firma(4,7),"2026-11-19T19:00:00.000000"}));
        var r=service.tomar(4,comando(),autenticacion);
        assertTrue(r.repetida());verify(reserva).getResultList();verify(insert,never()).executeUpdate();
    }
}
