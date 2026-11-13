package com.sistema.bitacora.service;

import java.time.*;
import java.util.*;

/** Calcula intervalos informativos. No aparta espacios ni sustituye la confirmación. */
public final class DashboardAdminAgenda {
    private DashboardAdminAgenda() {}
    public record Reserva(String folio, LocalDate fecha, LocalTime inicio,
                          LocalTime fin, String estatus, String motivo, String solicitante) {}
    public record Tramo(String inicio, String fin, String estado, String detalle) {}
    private record Bloque(LocalDateTime inicio, LocalDateTime fin,
                          String estado, String detalle, int prioridad) {}

    public static List<Tramo> calcular(LocalDate fecha, Boolean activo,
            String estadoEspacio, Integer minutos, List<Reserva> reservas) {
        Objects.requireNonNull(fecha, "La fecha es obligatoria");
        if (!Boolean.TRUE.equals(activo)) return cerrado(fecha, "Espacio desactivado");
        if (!"DISPONIBLE".equals(normalizar(estadoEspacio)))
            return cerrado(fecha, "Espacio no habilitado: " + texto(estadoEspacio, "sin estado"));
        if (minutos == null || minutos <= 0)
            return cerrado(fecha, "La duración de limpieza requiere revisión");
        if (reservas == null) return cerrado(fecha, "No se pudieron comprobar las reservas");
        LocalDateTime desde = fecha.atStartOfDay(), hasta = fecha.plusDays(1).atStartOfDay();
        List<Bloque> bloques = new ArrayList<>();
        for (Reserva r : reservas) {
            if (r == null) return cerrado(fecha, "Hay un registro que requiere revisión");
            String estado = normalizar(r.estatus());
            if (Set.of("CANCELADA", "RECHAZADA").contains(estado)) continue;
            if (r.fecha() == null || r.inicio() == null || r.fin() == null || !r.fin().isAfter(r.inicio()))
                return cerrado(fecha, "Hay una reserva con horario inconsistente; debe revisarse");
            LocalDateTime inicio = r.fecha().atTime(r.inicio()), fin = r.fecha().atTime(r.fin());
            LocalDateTime previo, posterior;
            try {
                previo = inicio.minusMinutes(minutos);
                posterior = fin.plusMinutes(minutos);
            } catch (DateTimeException ex) {
                return cerrado(fecha, "El horario o la limpieza requiere revisión");
            }
            String folio = texto(r.folio(), "Sin folio");
            agregar(bloques, desde, hasta, previo, inicio, "MARGEN_PREVIO",
                    "Antes de " + folio + ": un evento nuevo debe terminar a más tardar "
                            + previo + " para dejar " + minutos + " minutos de limpieza", 2);
            String detalle = folio + " · " + texto(r.motivo(), "Evento registrado")
                    + " · " + texto(r.solicitante(), "Solicitante registrado");
            String etiqueta;
            int prioridad;
            if ("APROBADA".equals(estado)) { etiqueta = "OCUPADO"; prioridad = 5; }
            else if ("PENDIENTE".equals(estado)) {
                etiqueta = "PENDIENTE"; prioridad = 4;
                detalle += " · Guardada, pendiente de revisión: sí ocupa el espacio";
            } else {
                etiqueta = "NO_DISPONIBLE"; prioridad = 6;
                detalle += " · Estado que requiere revisión: " + texto(r.estatus(), "sin estado");
            }
            agregar(bloques, desde, hasta, inicio, fin, etiqueta, detalle, prioridad);
            agregar(bloques, desde, hasta, fin, posterior, "LIMPIEZA",
                    "Limpieza posterior a " + folio + " · " + minutos + " minutos", 3);
        }
        TreeSet<LocalDateTime> cortes = new TreeSet<>(List.of(desde, hasta));
        for (Bloque b : bloques) { cortes.add(b.inicio()); cortes.add(b.fin()); }
        List<LocalDateTime> puntos = new ArrayList<>(cortes);
        List<Tramo> resultado = new ArrayList<>();
        for (int i = 0; i + 1 < puntos.size(); i++) {
            LocalDateTime inicio = puntos.get(i), fin = puntos.get(i + 1);
            List<Bloque> presentes = bloques.stream()
                    .filter(b -> inicio.isBefore(b.fin()) && fin.isAfter(b.inicio()))
                    .sorted(Comparator.comparingInt(Bloque::prioridad).reversed()).toList();
            String estado = "DISPONIBLE";
            String detalle = "Sin bloqueo registrado. Deben comprobarse nuevamente horario, "
                    + "anticipación, recursos, documentos y cupo antes de confirmar";
            if (!presentes.isEmpty()) {
                estado = presentes.getFirst().estado();
                List<String> detalles = presentes.stream().map(Bloque::detalle).distinct().toList();
                detalle = String.join(" | ", detalles.stream().limit(8).toList());
                if (detalles.size() > 8) detalle += " | Hay más registros coincidentes";
            }
            Tramo nuevo = new Tramo(inicio.toString(), fin.toString(), estado, detalle);
            if (!resultado.isEmpty()) {
                Tramo anterior = resultado.getLast();
                if (anterior.fin().equals(nuevo.inicio()) && anterior.estado().equals(estado)
                        && anterior.detalle().equals(detalle)) {
                    resultado.set(resultado.size() - 1,
                            new Tramo(anterior.inicio(), nuevo.fin(), estado, detalle));
                    continue;
                }
            }
            resultado.add(nuevo);
        }
        return List.copyOf(resultado);
    }
    public static List<Tramo> cerrado(LocalDate fecha, String motivo) {
        return List.of(new Tramo(fecha.atStartOfDay().toString(),
                fecha.plusDays(1).atStartOfDay().toString(), "NO_DISPONIBLE", motivo));
    }
    private static void agregar(List<Bloque> bloques, LocalDateTime desde, LocalDateTime hasta,
            LocalDateTime inicio, LocalDateTime fin, String estado, String detalle, int prioridad) {
        LocalDateTime a = inicio.isBefore(desde) ? desde : inicio;
        LocalDateTime b = fin.isAfter(hasta) ? hasta : fin;
        if (a.isBefore(b)) bloques.add(new Bloque(a, b, estado, detalle, prioridad));
    }
    static String normalizar(String valor) {
        return valor == null ? "" : valor.strip().toUpperCase(Locale.ROOT);
    }
    private static String texto(String valor, String alternativo) {
        return valor == null || valor.isBlank() ? alternativo : valor.strip();
    }
}
