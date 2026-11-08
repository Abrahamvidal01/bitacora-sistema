package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Departamento;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

@Service
@Transactional(readOnly = true)
public class ReservaCupoValidacionService {

    private static final String CONSULTA_CONSUMOS = """
            SELECT COUNT(*)
            FROM consumo_cupos_reserva c
            INNER JOIN solicitudes_espacio s
                ON s.id = c.solicitud_id
            WHERE s.departamento_solicitante_id = :departamentoId
              AND s.fecha_evento BETWEEN :desde AND :hasta
              AND c.fecha_reintegro IS NULL
            """;

    private final EntityManager entityManager;

    public ReservaCupoValidacionService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Consulta disponibilidad de cupo.
     * No registra consumos ni garantiza exclusividad al confirmar.
     */
    public ResultadoCupo validar(
            Long departamentoId,
            LocalDate fechaEvento) {

        if (departamentoId == null || departamentoId <= 0) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Indica un departamento válido"
            );
        }

        if (fechaEvento == null
                || fechaEvento.getYear() < 1000
                || fechaEvento.getYear() > 9999) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Indica una fecha de evento válida"
            );
        }

        Departamento departamento = entityManager.find(
                Departamento.class,
                departamentoId
        );

        if (departamento == null) {
            throw error(
                    HttpStatus.NOT_FOUND,
                    "El departamento solicitante no existe"
            );
        }

        if (!Boolean.TRUE.equals(departamento.getActivo())) {
            throw error(
                    HttpStatus.CONFLICT,
                    "El departamento solicitante está desactivado"
            );
        }

        Integer limite = departamento.getCupoMensual();

        if (limite == null || (limite != 1 && limite != 4)) {
            throw error(
                    HttpStatus.CONFLICT,
                    "El departamento no tiene un cupo mensual válido. "
                            + "Revisa su configuración"
            );
        }

        YearMonth mes = YearMonth.from(fechaEvento);

        Query consulta = entityManager.createNativeQuery(
                CONSULTA_CONSUMOS
        );

        consulta.setParameter("departamentoId", departamentoId);
        consulta.setParameter("desde", mes.atDay(1));
        consulta.setParameter("hasta", mes.atEndOfMonth());

        long consumidos = convertirConteo(consulta.getSingleResult());

        if (consumidos >= limite) {
            throw error(
                    HttpStatus.CONFLICT,
                    "El departamento agotó su cupo de "
                            + limite + " reserva(s) para " + mes
            );
        }

        return new ResultadoCupo(
                departamentoId,
                mes.toString(),
                limite,
                consumidos,
                limite.longValue() - consumidos
        );
    }

    private long convertirConteo(Object valor) {
        if (valor instanceof Number numero) {
            try {
                long cantidad = new BigDecimal(
                        numero.toString()
                ).longValueExact();

                if (cantidad >= 0) {
                    return cantidad;
                }
            } catch (NumberFormatException | ArithmeticException ex) {
                throw conteoInvalido();
            }
        }

        throw conteoInvalido();
    }

    private ResponseStatusException conteoInvalido() {
        return error(
                HttpStatus.CONFLICT,
                "No se pudo obtener un conteo válido de los cupos. "
                        + "Revisa los registros antes de continuar"
        );
    }

    private ResponseStatusException error(
            HttpStatus estado,
            String mensaje) {

        return new ResponseStatusException(estado, mensaje);
    }

    public record ResultadoCupo(
            Long departamentoId,
            String mesEvento,
            int cupoMensual,
            long consumidos,
            long disponibles) {
    }
}