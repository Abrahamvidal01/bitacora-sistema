package com.sistema.bitacora.service;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DashboardAdminAgendaTest {
    private static final LocalDate FECHA = LocalDate.of(2026, 11, 16);
    private DashboardAdminAgenda.Reserva reserva(LocalDate fecha, String inicio, String fin, String estado) {
        return new DashboardAdminAgenda.Reserva("RES-2026-000001", fecha,
                LocalTime.parse(inicio), LocalTime.parse(fin), estado, "Evento", "Titular");
    }
    private List<DashboardAdminAgenda.Tramo> calcular(DashboardAdminAgenda.Reserva... reservas) {
        return DashboardAdminAgenda.calcular(FECHA, true, "DISPONIBLE", 30, List.of(reservas));
    }
    private void tramo(DashboardAdminAgenda.Tramo t, String inicio, String fin, String estado) {
        assertEquals(inicio, t.inicio()); assertEquals(fin, t.fin()); assertEquals(estado, t.estado());
    }
    @Test void sinReservasMuestraDiaCompleto() {
        var r = calcular(); assertEquals(1, r.size());
        tramo(r.getFirst(), "2026-11-16T00:00", "2026-11-17T00:00", "DISPONIBLE");
    }
    @Test void separaMargenEventoYLimpieza() {
        var r = calcular(reserva(FECHA, "10:00", "11:00", "APROBADA"));
        assertEquals(5, r.size());
        tramo(r.get(0), "2026-11-16T00:00", "2026-11-16T09:30", "DISPONIBLE");
        tramo(r.get(1), "2026-11-16T09:30", "2026-11-16T10:00", "MARGEN_PREVIO");
        tramo(r.get(2), "2026-11-16T10:00", "2026-11-16T11:00", "OCUPADO");
        tramo(r.get(3), "2026-11-16T11:00", "2026-11-16T11:30", "LIMPIEZA");
        tramo(r.get(4), "2026-11-16T11:30", "2026-11-17T00:00", "DISPONIBLE");
    }
    @Test void pendienteBloquea() {
        var r = calcular(reserva(FECHA, "10:00", "11:00", "PENDIENTE"));
        assertTrue(r.stream().anyMatch(t -> t.estado().equals("PENDIENTE") && t.detalle().contains("sí ocupa")));
    }
    @Test void canceladaYRechazadaNoBloquean() {
        var r = calcular(reserva(FECHA, "10:00", "11:00", "CANCELADA"),
                reserva(FECHA, "12:00", "13:00", "RECHAZADA"));
        assertEquals(1, r.size()); assertEquals("DISPONIBLE", r.getFirst().estado());
    }
    @Test void estadoDesconocidoBloquea() {
        var r = calcular(reserva(FECHA, "10:00", "11:00", "DESCONOCIDO"));
        assertTrue(r.stream().anyMatch(t -> t.inicio().equals("2026-11-16T10:00") && t.estado().equals("NO_DISPONIBLE")));
    }
    @Test void limpiezaAnteriorCruzaMedianoche() {
        var r = calcular(reserva(FECHA.minusDays(1), "23:00", "23:50", "APROBADA"));
        tramo(r.getFirst(), "2026-11-16T00:00", "2026-11-16T00:20", "LIMPIEZA");
    }
    @Test void reservaSiguienteExigeMargenAnterior() {
        var r = calcular(reserva(FECHA.plusDays(1), "00:10", "01:00", "APROBADA"));
        tramo(r.getLast(), "2026-11-16T23:40", "2026-11-17T00:00", "MARGEN_PREVIO");
    }
    @Test void mantenimientoNuncaSeMuestraLibre() {
        var r = DashboardAdminAgenda.calcular(FECHA, true, "MANTENIMIENTO", 30, List.of());
        assertEquals(1, r.size()); assertEquals("NO_DISPONIBLE", r.getFirst().estado());
    }
    @Test void desactivadoNuncaSeMuestraLibre() {
        var r = DashboardAdminAgenda.calcular(FECHA, false, "DISPONIBLE", 30, List.of());
        assertEquals("NO_DISPONIBLE", r.getFirst().estado());
    }
    @Test void limpiezaInvalidaCierraDisponibilidad() {
        for (Integer minutos : new Integer[]{null, 0, -1}) {
            var r = DashboardAdminAgenda.calcular(FECHA, true, "DISPONIBLE", minutos, List.of());
            assertEquals(1, r.size()); assertEquals("NO_DISPONIBLE", r.getFirst().estado());
        }
    }
    @Test void horarioInconsistenteNoDejaHuecosVerdes() {
        var r = calcular(reserva(FECHA, "11:00", "10:00", "PENDIENTE"));
        assertEquals(1, r.size()); assertEquals("NO_DISPONIBLE", r.getFirst().estado());
    }
    @Test void superposicionesNoProducenFalsaDisponibilidad() {
        var r = calcular(reserva(FECHA, "10:00", "12:00", "APROBADA"),
                reserva(FECHA, "11:00", "13:00", "PENDIENTE"));
        assertFalse(r.stream().anyMatch(t -> t.estado().equals("DISPONIBLE")
                && t.inicio().compareTo("2026-11-16T13:30") < 0
                && t.fin().compareTo("2026-11-16T09:30") > 0));
        for (int i = 1; i < r.size(); i++) assertEquals(r.get(i - 1).fin(), r.get(i).inicio());
    }
    @Test void margenPuedeSuperarUnDia() {
        var r = DashboardAdminAgenda.calcular(FECHA, true, "DISPONIBLE", 1500,
                List.of(reserva(FECHA.plusDays(1), "10:00", "11:00", "APROBADA")));
        tramo(r.getLast(), "2026-11-16T09:00", "2026-11-17T00:00", "MARGEN_PREVIO");
    }
    @Test void listaDesconocidaNoEquivaleAListaVacia() {
        var r = DashboardAdminAgenda.calcular(FECHA, true, "DISPONIBLE", 30, null);
        assertEquals("NO_DISPONIBLE", r.getFirst().estado());
    }
}
