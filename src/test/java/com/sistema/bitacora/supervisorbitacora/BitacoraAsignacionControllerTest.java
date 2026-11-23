package com.sistema.bitacora.supervisorbitacora;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unitarias directas. NO prueban AOP, cadena HTTP/CSRF, SQL real ni concurrencia. */
class BitacoraAsignacionControllerTest {
    private EntityManager em;
    private Query actor,filas;
    private BitacoraAsignacionService service;
    private BitacoraAsignacionController controller;
    private Authentication auth;
    @BeforeEach void preparar(){
        em=mock(EntityManager.class);actor=mock(Query.class,RETURNS_SELF);filas=mock(Query.class,RETURNS_SELF);
        service=mock(BitacoraAsignacionService.class);
        when(em.createNativeQuery(anyString())).thenAnswer(c->((String)c.getArgument(0)).contains("COALESCE(MAX(u.id),0)")?actor:filas);
        when(actor.getSingleResult()).thenReturn(7L);when(filas.getResultList()).thenReturn(List.of());
        auth=new UsernamePasswordAuthenticationToken("supervisor@example.invalid","",List.of());
        controller=new BitacoraAsignacionController(em,service);
    }
    @Test void sinAutenticacionNoConsulta(){
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.candidatas("2026-12",null)).getStatusCode().value());
        verifyNoInteractions(em);
    }
    @Test void rolNoVigenteNoLista(){
        when(actor.getSingleResult()).thenReturn(0L);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.candidatas("2026-12",auth)).getStatusCode().value());
        verifyNoInteractions(filas);
    }
    @Test void consultaAcotadaYConActor(){
        var respuesta=controller.candidatas("2026-12",auth);
        assertEquals("no-store",respuesta.getHeaders().getCacheControl());assertTrue(respuesta.getBody().reservas().isEmpty());
        verify(filas).setParameter("actor",7L);verify(filas).setMaxResults(201);
        verify(filas,never()).executeUpdate();
    }
    @Test void candidataNoExponeDatosPrivadosEnSuContrato(){
        Set<String> campos=Arrays.stream(BitacoraAsignacionController.Candidata.class.getRecordComponents()).map(c->c.getName()).collect(Collectors.toSet());
        assertEquals(Set.of("id","folio","fecha","inicio","fin","espacio","asignacion"),campos);
        when(filas.getResultList()).thenReturn(Collections.singletonList(new Object[]{4L,"RES-PRUEBA","2026-12-03","10:00","12:00","Espacio ficticio",null}));
        assertEquals("DISPONIBLE_PARA_TOMAR",controller.candidatas("2026-12",auth).getBody().reservas().getFirst().asignacion());
    }
    @Test void noTruncaResultados(){
        when(filas.getResultList()).thenReturn(Collections.nCopies(201,new Object[7]));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->controller.candidatas("2026-12",auth)).getStatusCode().value());
    }
    @Test void csrfCompruebaCuentaYNoCachea(){
        CsrfToken token=mock(CsrfToken.class);when(token.getHeaderName()).thenReturn("X-CSRF-TOKEN");when(token.getToken()).thenReturn("TOKEN-FICTICIO");
        var respuesta=controller.csrf(auth,token);
        assertEquals("no-store",respuesta.getHeaders().getCacheControl());assertEquals("TOKEN-FICTICIO",respuesta.getBody().token());
        verify(actor).getSingleResult();
    }
    @Test void postDelegaCuentaAutenticadaSinAdmitirActorEnComando(){
        var comando=new BitacoraAsignacionService.Tomar("b3253152-5954-4d3c-9334-1da93006f30e");
        var esperado=new BitacoraAsignacionService.Resultado(4,7,"2026-11-19T19:00Z",false);
        when(service.tomar(4,comando,auth)).thenReturn(esperado);
        assertEquals(esperado,controller.tomar(4,comando,auth).getBody());verify(service).tomar(4,comando,auth);
    }
    @Test void erroresDistinguenNegocioDeResultadoIncierto(){
        var conflicto=controller.negocio(new ResponseStatusException(HttpStatus.CONFLICT,"Asignada"));
        assertEquals(409,conflicto.getStatusCode().value());assertFalse(conflicto.getBody().resultadoIncierto());
        var incierto=controller.incierto(new IllegalStateException("NO PUBLICAR DETALLE INTERNO"));
        assertEquals(503,incierto.getStatusCode().value());assertTrue(incierto.getBody().resultadoIncierto());
        assertFalse(incierto.getBody().mensaje().contains("NO PUBLICAR"));
    }
}
