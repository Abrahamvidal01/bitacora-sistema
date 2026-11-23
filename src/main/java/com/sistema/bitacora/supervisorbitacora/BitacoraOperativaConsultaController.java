package com.sistema.bitacora.supervisorbitacora;

import jakarta.persistence.EntityManager;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

/** Consulta inicial solamente: no registra entregas, avisos ni correos. */
@Controller
@ResponseBody
@RequestMapping("/supervisor/api/bitacora-operativa")
@PreAuthorize("hasRole('SUPERVISOR')")
public class BitacoraOperativaConsultaController {
    private final EntityManager em;
    public BitacoraOperativaConsultaController(EntityManager em) { this.em = em; }
    public record Reserva(long id, String folio, String fecha, String inicio, String fin,
            String espacio, String departamento, String evento, String recursosExternos,
            String estadoBitacora, String entradaReal, String salidaReal) {}
    public record Mes(String mes, List<Reserva> reservas) {}
    static YearMonth validarMes(String mes) {
        if (mes == null || !mes.matches("[0-9]{4}-(0[1-9]|1[0-2])")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mes no valido");
        }
        try {
            YearMonth valor = YearMonth.parse(mes);
            if (valor.getYear() < 1000 || valor.getYear() > 9998) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mes fuera de rango");
            }
            return valor;
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mes no valido");
        }
    }
    @GetMapping
    @Transactional(readOnly = true, timeout = 20)
    public ResponseEntity<Mes> consultar(@RequestParam("mes") String mes, Authentication actor) {
        if (actor == null || !actor.isAuthenticated() || actor.getName() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso no autorizado");
        }
        Number acceso = (Number) em.createNativeQuery("""
            SELECT COALESCE(MAX(u.id),0) FROM usuarios u
            JOIN usuario_roles ur ON ur.usuario_id=u.id
            JOIN roles r ON r.id=ur.rol_id
            WHERE u.correo_institucional=:correo AND u.activo=1
              AND r.nombre_rol='SUPERVISOR'
            """).setParameter("correo", actor.getName()).getSingleResult();
        if (acceso.longValue() == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso no autorizado");
        }
        YearMonth periodo = validarMes(mes);
        @SuppressWarnings("unchecked")
        List<Object[]> filas = em.createNativeQuery("""
            SELECT s.id,s.folio,DATE_FORMAT(s.fecha_evento,'%Y-%m-%d'),
                   TIME_FORMAT(s.hora_inicio,'%H:%i'),TIME_FORMAT(s.hora_fin,'%H:%i'),
                   e.nombre_espacio,d.nombre_departamento,s.motivo_evento,s.recursos_externos,
                   b.estatus_bitacora,
                   DATE_FORMAT(b.hora_entrada_real,'%Y-%m-%d %H:%i'),
                   DATE_FORMAT(b.hora_salida_real,'%Y-%m-%d %H:%i'),b.id
            FROM solicitudes_espacio s
            JOIN espacios e ON e.id=s.espacio_id
            JOIN departamentos d ON d.id=s.departamento_solicitante_id
            LEFT JOIN bitacora_espacios b ON b.solicitud_id=s.id
            LEFT JOIN bitacora_operativa_asignaciones a ON a.solicitud_id=s.id
            WHERE s.estatus_solicitud='APROBADA'
              AND s.fecha_evento>=:desde AND s.fecha_evento<:hasta
              AND (s.usuario_id=:actor OR s.registrado_por_usuario_id=:actor OR a.supervisor_usuario_id=:actor)
            ORDER BY s.fecha_evento,s.hora_inicio,s.id
            """)
            .setParameter("desde", java.sql.Date.valueOf(periodo.atDay(1)))
            .setParameter("hasta", java.sql.Date.valueOf(periodo.plusMonths(1).atDay(1)))
            .setParameter("actor", acceso.longValue())
            .setMaxResults(201).getResultList();
        if (filas.size() > 200) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El mes supera 200 reservas; se requiere paginacion. No se muestran resultados recortados");
        }
        List<Reserva> reservas = filas.stream().map(f -> new Reserva(
                ((Number) f[0]).longValue(), texto(f[1]), texto(f[2]), texto(f[3]), texto(f[4]),
                texto(f[5]), texto(f[6]), texto(f[7]), texto(f[8]),
                f[12] == null ? "SIN REGISTRO" : (f[9] == null ? "ESTADO NO INFORMADO" : texto(f[9])),
                texto(f[10]), texto(f[11]))).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Mes(mes, reservas));
    }
    private static String texto(Object valor) { return valor == null ? "" : valor.toString(); }
}
