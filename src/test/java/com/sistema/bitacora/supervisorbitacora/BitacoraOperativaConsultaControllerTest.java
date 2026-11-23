package com.sistema.bitacora.supervisorbitacora;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Date;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unitarias con mocks: no prueba autorizacion HTTP, SQL real ni navegador. */
class BitacoraOperativaConsultaControllerTest {
    private EntityManager em;
    private Query acceso;
    private Query filas;
    private BitacoraOperativaConsultaController controller;
    private Authentication actor;
    @BeforeEach void preparar() {
        em = mock(EntityManager.class);
        acceso = mock(Query.class, RETURNS_SELF);
        filas = mock(Query.class, RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            return sql.contains("COALESCE(MAX(u.id),0) FROM usuarios") ? acceso : filas;
        });
        when(acceso.getSingleResult()).thenReturn(1L);
        when(filas.getResultList()).thenReturn(List.of());
        controller = new BitacoraOperativaConsultaController(em);
        actor = new UsernamePasswordAuthenticationToken("supervisor@example.invalid", "", List.of());
    }
    @Test void rechazaEntradaDeMesInvalida() {
        for (String mes : new String[]{"2026-13", "2026-1", "2026-01 OR 1=1", "0000-01", "9999-12"}) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> BitacoraOperativaConsultaController.validarMes(mes)).getStatusCode().value());
        }
        assertThrows(ResponseStatusException.class, () -> BitacoraOperativaConsultaController.validarMes(null));
    }
    @Test void sinAutenticacionNoConsultaBase() {
        assertEquals(403, assertThrows(ResponseStatusException.class,
            () -> controller.consultar("2026-12", null)).getStatusCode().value());
        verifyNoInteractions(em);
    }
    @Test void cuentaSinRolVigenteNoLista() {
        when(acceso.getSingleResult()).thenReturn(0L);
        assertEquals(403, assertThrows(ResponseStatusException.class,
            () -> controller.consultar("2026-12", actor)).getStatusCode().value());
        verifyNoInteractions(filas);
    }
    @Test void mesVacioSinEscriturasConLimitesYNoStore() {
        var respuesta = controller.consultar("2026-12", actor);
        assertNotNull(respuesta.getBody());
        assertTrue(respuesta.getBody().reservas().isEmpty());
        assertEquals("no-store", respuesta.getHeaders().getCacheControl());
        verify(filas).setParameter("desde", Date.valueOf("2026-12-01"));
        verify(filas).setParameter("hasta", Date.valueOf("2027-01-01"));
        verify(filas).setParameter("actor", 1L);
        verify(filas).setMaxResults(201);
        verify(filas, never()).executeUpdate(); verify(acceso, never()).executeUpdate();
    }
    @Test void mapeaReservaSinInventarRegistroDeEntrega() {
        Object[] fila = {4L,"RES-PRUEBA","2026-12-03","10:00","12:00","Espacio ficticio",
            "Departamento ficticio","Evento ficticio","2 mesas externas",null,null,null,null};
        when(filas.getResultList()).thenReturn(Collections.singletonList(fila));
        var reserva = controller.consultar("2026-12", actor).getBody().reservas().getFirst();
        assertEquals("SIN REGISTRO", reserva.estadoBitacora());
        assertEquals("", reserva.entradaReal());
        assertEquals("2 mesas externas", reserva.recursosExternos());
        verify(filas, never()).executeUpdate();
    }
    @Test void noRecortaMesSilenciosamente() {
        when(filas.getResultList()).thenReturn(Collections.nCopies(201, new Object[13]));
        assertEquals(409, assertThrows(ResponseStatusException.class,
            () -> controller.consultar("2026-12", actor)).getStatusCode().value());
    }
}
