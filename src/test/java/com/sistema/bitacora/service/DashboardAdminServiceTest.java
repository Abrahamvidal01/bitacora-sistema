package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.SolicitudEspacio;
import jakarta.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Date;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardAdminServiceTest {
    private EntityManager entidad;
    private DashboardAdminService servicio;
    @BeforeEach void preparar() {
        entidad = mock(EntityManager.class);
        servicio = new DashboardAdminService(entidad, Clock.fixed(
                Instant.parse("2026-12-01T02:00:00Z"), ZoneId.of("America/Mexico_City")));
    }
    @Test @SuppressWarnings("unchecked")
    void resumenUsaMesLocalCuentasActivasYModalidadesUrgentes() {
        TypedQuery<Long> usuarios = mock(TypedQuery.class), espacios = mock(TypedQuery.class);
        Query urgencias = mock(Query.class);
        when(entidad.createQuery("select count(u) from Usuario u where u.activo = true", Long.class)).thenReturn(usuarios);
        when(entidad.createQuery("select count(e) from Espacio e where e.activo = true", Long.class)).thenReturn(espacios);
        when(usuarios.getSingleResult()).thenReturn(7L);
        when(espacios.getSingleResult()).thenReturn(2L);
        when(entidad.createNativeQuery(anyString())).thenReturn(urgencias);
        when(urgencias.getSingleResult()).thenReturn(3L);
        var r = servicio.resumen(null);
        assertEquals("2026-11", r.mes()); assertEquals("2026-11-30", r.fechaActual());
        assertEquals(7L, r.usuariosActivos()); assertEquals(2L, r.espaciosActivos());
        assertEquals(3L, r.urgenciasDelMes());
        verify(urgencias).setParameter("desde", Date.valueOf("2026-11-01"));
        verify(urgencias).setParameter("hasta", Date.valueOf("2026-12-01"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entidad).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("'URGENTE_INVENTARIO'"));
        assertTrue(sql.getValue().contains("'URGENTE_REQUISICION'"));
        assertFalse(sql.getValue().contains("REGISTRO_ADMIN"));
    }
    @Test void fechasYMesesInvalidosSeRechazanAntesDeConsultar() {
        assertThrows(ResponseStatusException.class, () -> servicio.resumen("2026-13"));
        assertThrows(ResponseStatusException.class, () -> servicio.resumen("2026-1"));
        assertThrows(ResponseStatusException.class, () -> servicio.agenda("2026-02-30", null));
        assertThrows(ResponseStatusException.class, () -> servicio.agenda("2026-11-16", 0L));
        assertThrows(ResponseStatusException.class, () -> servicio.historial("2026-11", -1));
        verifyNoInteractions(entidad);
    }
    @Test void paginaExcesivaSeRechazaAntesDeConsultar() {
        assertThrows(ResponseStatusException.class, () -> servicio.historial("2026-11", 100001));
        verifyNoInteractions(entidad);
    }
    @Test @SuppressWarnings("unchecked")
    void agendaConsultaFechasVecinasSinFiltrarModalidadNiEstado() {
        Espacio e = espacio("DISPONIBLE", 1500);
        TypedQuery<Espacio> espacios = mock(TypedQuery.class);
        TypedQuery<SolicitudEspacio> reservas = mock(TypedQuery.class);
        when(entidad.createQuery(anyString(), eq(Espacio.class))).thenReturn(espacios);
        when(espacios.setParameter(anyString(), any())).thenReturn(espacios);
        when(espacios.setMaxResults(anyInt())).thenReturn(espacios);
        when(espacios.getResultList()).thenReturn(List.of(e));
        when(entidad.createQuery(anyString(), eq(SolicitudEspacio.class))).thenReturn(reservas);
        when(reservas.setParameter(anyString(), any())).thenReturn(reservas);
        when(reservas.setMaxResults(anyInt())).thenReturn(reservas);
        when(reservas.getResultList()).thenReturn(List.of());
        var r = servicio.agenda("2026-11-16", 9L);
        assertEquals(1, r.espacios().size());
        verify(reservas).setParameter("desde", LocalDate.of(2026, 11, 14));
        verify(reservas).setParameter("hasta", LocalDate.of(2026, 11, 18));
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(entidad).createQuery(jpql.capture(), eq(SolicitudEspacio.class));
        assertFalse(jpql.getValue().contains("estatusSolicitud"));
        assertFalse(jpql.getValue().contains("tipoOrigen"));
        assertFalse(jpql.getValue().contains("modalidad"));
    }
    @Test @SuppressWarnings("unchecked")
    void mantenimientoCierraDisponibilidadSinConsultarReservas() {
        TypedQuery<Espacio> espacios = mock(TypedQuery.class);
        when(entidad.createQuery(anyString(), eq(Espacio.class))).thenReturn(espacios);
        when(espacios.setParameter(anyString(), any())).thenReturn(espacios);
        when(espacios.setMaxResults(anyInt())).thenReturn(espacios);
        when(espacios.getResultList()).thenReturn(List.of(espacio("MANTENIMIENTO", 30)));
        var r = servicio.agenda("2026-11-16", 9L);
        assertEquals("NO_DISPONIBLE", r.espacios().getFirst().tramos().getFirst().estado());
        verify(entidad, never()).createQuery(anyString(), eq(SolicitudEspacio.class));
    }
    private Espacio espacio(String estado, int minutos) {
        Espacio e = new Espacio(); e.setId(9L); e.setNombreEspacio("Sala de prueba");
        e.setActivo(true); e.setEstadoActual(estado); e.setMinutosLimpieza(minutos);
        return e;
    }
}
