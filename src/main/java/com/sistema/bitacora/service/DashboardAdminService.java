package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.SolicitudEspacio;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Date;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class DashboardAdminService {
    private static final int TAMANIO_PAGINA = 20;
    // Mismo filtro para el contador y el historial. La agenda NO usa este filtro.
    private static final String BASE_HISTORIAL = """
            FROM solicitudes_espacio s
            JOIN confirmaciones_reserva f ON f.solicitud_id = s.id
            JOIN usuarios u ON u.id = s.usuario_id
            JOIN espacios e ON e.id = s.espacio_id
            JOIN departamentos d ON d.id = s.departamento_solicitante_id
            WHERE f.modalidad IN ('URGENTE_INVENTARIO', 'URGENTE_REQUISICION')
              AND s.fecha_evento >= :desde AND s.fecha_evento < :hasta
            """;
    private final EntityManager entidad;
    private final Clock reloj;
    public DashboardAdminService(EntityManager entidad, @Qualifier("relojReservas") Clock reloj) {
        this.entidad = entidad;
        this.reloj = reloj;
    }
    public Resumen resumen(String valorMes) {
        YearMonth mes = mesValido(valorMes);
        long usuarios = entidad.createQuery(
                "select count(u) from Usuario u where u.activo = true", Long.class).getSingleResult();
        long espacios = entidad.createQuery(
                "select count(e) from Espacio e where e.activo = true", Long.class).getSingleResult();
        return new Resumen(LocalDate.now(reloj).toString(), mes.toString(), reloj.getZone().getId(),
                usuarios, espacios, contar(mes));
    }
    public List<EspacioOpcion> espacios() {
        List<Espacio> lista = entidad.createQuery("""
                select e from Espacio e order by e.nombreEspacio asc, e.id asc
                """, Espacio.class).setMaxResults(1001).getResultList();
        if (lista.size() > 1000) throw error(HttpStatus.CONFLICT,
                "Hay demasiados espacios; el selector requiere paginación");
        return lista.stream().map(e -> new EspacioOpcion(e.getId(), e.getNombreEspacio(),
                Boolean.TRUE.equals(e.getActivo()), e.getEstadoActual())).toList();
    }
    public Historial historial(String valorMes, int paginaSolicitada) {
        YearMonth mes = mesValido(valorMes);
        if (paginaSolicitada < 0 || paginaSolicitada > 100000)
            throw error(HttpStatus.BAD_REQUEST, "La página solicitada no es válida");
        long total = contar(mes);
        long paginas = Math.max(1L, (total + TAMANIO_PAGINA - 1) / TAMANIO_PAGINA);
        int pagina = (int) Math.min(paginaSolicitada, paginas - 1);
        Query consulta = entidad.createNativeQuery("""
                SELECT s.folio, u.nombre_completo, d.nombre_departamento,
                       e.nombre_espacio, s.fecha_evento, s.hora_inicio,
                       s.hora_fin, f.fecha_registro, s.estatus_solicitud, f.modalidad
                """ + BASE_HISTORIAL + " ORDER BY s.fecha_evento DESC, s.hora_inicio DESC, s.id DESC");
        parametrosMes(consulta, mes);
        consulta.setFirstResult(Math.multiplyExact(pagina, TAMANIO_PAGINA));
        consulta.setMaxResults(TAMANIO_PAGINA);
        List<ReservaHistorial> filas = new ArrayList<>();
        for (Object fila : consulta.getResultList()) {
            if (!(fila instanceof Object[] c) || c.length != 10)
                throw new IllegalStateException("Formato de historial no válido");
            filas.add(new ReservaHistorial(valor(c[0]), valor(c[1]), valor(c[2]), valor(c[3]),
                    valor(c[4]), valor(c[5]), valor(c[6]), valor(c[7]), valor(c[8]), valor(c[9])));
        }
        return new Historial(mes.toString(), pagina, total, paginas, List.copyOf(filas));
    }
    public Agenda agenda(String valorFecha, Long espacioId) {
        LocalDate fecha = fechaValida(valorFecha);
        if (espacioId != null && espacioId <= 0)
            throw error(HttpStatus.BAD_REQUEST, "Selecciona un espacio válido");
        TypedQuery<Espacio> consulta = entidad.createQuery("select e from Espacio e"
                + (espacioId == null ? "" : " where e.id = :id")
                + " order by e.nombreEspacio asc, e.id asc", Espacio.class);
        if (espacioId != null) consulta.setParameter("id", espacioId);
        List<Espacio> espacios = consulta.setMaxResults(51).getResultList();
        if (espacios.size() > 50) throw error(HttpStatus.CONFLICT,
                "Hay más de 50 espacios. Selecciona uno para consultar su agenda");
        if (espacioId != null && espacios.isEmpty())
            throw error(HttpStatus.NOT_FOUND, "El espacio seleccionado ya no existe");
        List<AgendaEspacio> resultado = new ArrayList<>();
        int totalConsultadas = 0;
        for (Espacio e : espacios) {
            Integer minutos = e.getMinutosLimpieza();
            List<DashboardAdminAgenda.Reserva> reservas = new ArrayList<>();
            if (Boolean.TRUE.equals(e.getActivo())
                    && "DISPONIBLE".equals(DashboardAdminAgenda.normalizar(e.getEstadoActual()))
                    && minutos != null && minutos > 0) {
                // Incluye ambos lados y márgenes de más de un día sin recortar la configuración.
                LocalDate desde = fecha.atStartOfDay().minusMinutes(minutos).toLocalDate();
                LocalDate hasta = fecha.plusDays(1).atStartOfDay().plusMinutes(minutos).toLocalDate();
                if (desde.getYear() < 1000 || hasta.getYear() > 9999) {
                    resultado.add(new AgendaEspacio(e.getId(), e.getNombreEspacio(), minutos,
                            DashboardAdminAgenda.cerrado(fecha, "La limpieza requiere revisión")));
                    continue;
                }
                List<SolicitudEspacio> solicitudes = entidad.createQuery("""
                        select s from SolicitudEspacio s join fetch s.usuario
                        where s.espacio.id = :espacioId
                          and (s.fechaEvento between :desde and :hasta or s.fechaEvento is null)
                        order by s.fechaEvento asc, s.horaInicio asc, s.id asc
                        """, SolicitudEspacio.class)
                        .setParameter("espacioId", e.getId()).setParameter("desde", desde)
                        .setParameter("hasta", hasta).setMaxResults(1001).getResultList();
                totalConsultadas += solicitudes.size();
                if (solicitudes.size() > 1000 || totalConsultadas > 5000)
                    throw error(HttpStatus.CONFLICT,
                            "Demasiados registros. Selecciona un espacio o revisa la duración de limpieza");
                for (SolicitudEspacio s : solicitudes) reservas.add(new DashboardAdminAgenda.Reserva(
                        s.getFolio(), s.getFechaEvento(), s.getHoraInicio(), s.getHoraFin(),
                        s.getEstatusSolicitud(), s.getMotivoEvento(),
                        s.getUsuario() == null ? null : s.getUsuario().getNombreCompleto()));
            }
            resultado.add(new AgendaEspacio(e.getId(), e.getNombreEspacio(), minutos,
                    DashboardAdminAgenda.calcular(fecha, e.getActivo(), e.getEstadoActual(), minutos, reservas)));
        }
        return new Agenda(fecha.toString(), reloj.getZone().getId(), List.copyOf(resultado));
    }
    private long contar(YearMonth mes) {
        Query consulta = entidad.createNativeQuery("SELECT COUNT(*) " + BASE_HISTORIAL);
        parametrosMes(consulta, mes);
        Object valor = consulta.getSingleResult();
        if (!(valor instanceof Number numero) || numero.longValue() < 0)
            throw new IllegalStateException("No se pudo obtener el total");
        return numero.longValue();
    }
    private void parametrosMes(Query consulta, YearMonth mes) {
        consulta.setParameter("desde", Date.valueOf(mes.atDay(1)));
        consulta.setParameter("hasta", Date.valueOf(mes.plusMonths(1).atDay(1)));
    }
    private YearMonth mesValido(String valor) {
        if (valor == null || valor.isBlank()) return YearMonth.now(reloj);
        try {
            if (!valor.matches("\\d{4}-\\d{2}")) throw new DateTimeParseException("Formato", valor, 0);
            YearMonth mes = YearMonth.parse(valor);
            if (mes.getYear() < 1900 || mes.getYear() > 2200)
                throw new DateTimeParseException("Rango", valor, 0);
            return mes;
        } catch (DateTimeParseException ex) {
            throw error(HttpStatus.BAD_REQUEST, "Selecciona un mes válido entre 1900 y 2200");
        }
    }
    private LocalDate fechaValida(String valor) {
        if (valor == null || valor.isBlank()) return LocalDate.now(reloj);
        try {
            if (!valor.matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeParseException("Formato", valor, 0);
            LocalDate fecha = LocalDate.parse(valor);
            if (fecha.getYear() < 1900 || fecha.getYear() > 2200)
                throw new DateTimeParseException("Rango", valor, 0);
            return fecha;
        } catch (DateTimeParseException ex) {
            throw error(HttpStatus.BAD_REQUEST, "Selecciona una fecha válida entre 1900 y 2200");
        }
    }
    private static String valor(Object o) { return o == null ? "" : o.toString(); }
    private static ResponseStatusException error(HttpStatus status, String mensaje) {
        return new ResponseStatusException(status, mensaje);
    }
    public record Resumen(String fechaActual, String mes, String zonaHoraria,
                          long usuariosActivos, long espaciosActivos, long urgenciasDelMes) {}
    public record EspacioOpcion(Long id, String nombre, boolean activo, String estado) {}
    public record ReservaHistorial(String folio, String solicitante, String departamento,
            String espacio, String fecha, String inicio, String fin, String registro,
            String estado, String modalidad) {}
    public record Historial(String mes, int pagina, long total, long totalPaginas,
                            List<ReservaHistorial> reservas) {}
    public record Agenda(String fecha, String zonaHoraria, List<AgendaEspacio> espacios) {}
    public record AgendaEspacio(Long id, String nombre, Integer minutosLimpieza,
                               List<DashboardAdminAgenda.Tramo> tramos) {}
}
