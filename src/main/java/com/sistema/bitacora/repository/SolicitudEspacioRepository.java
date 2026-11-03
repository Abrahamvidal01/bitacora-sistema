package com.sistema.bitacora.repository;

import com.sistema.bitacora.entity.SolicitudEspacio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Repository
public interface SolicitudEspacioRepository extends JpaRepository<SolicitudEspacio, Long> {

    List<SolicitudEspacio> findByEspacio_IdAndFechaEventoOrderByHoraInicioAsc(
            Long espacioId, LocalDate fechaEvento);

    // Se conserva por compatibilidad. No incluye limpieza.
    @Query("SELECT s FROM SolicitudEspacio s WHERE s.espacio.id = :espacioId " +
            "AND s.fechaEvento = :fechaEvento " +
            "AND s.estatusSolicitud IN ('APROBADA', 'PENDIENTE') " +
            "AND (:horaInicio < s.horaFin AND :horaFin > s.horaInicio)")
    List<SolicitudEspacio> buscarConflictosDeHorario(
            @Param("espacioId") Long espacioId,
            @Param("fechaEvento") LocalDate fechaEvento,
            @Param("horaInicio") LocalTime horaInicio,
            @Param("horaFin") LocalTime horaFin);

    // Incluye fechas vecinas cuando el margen de limpieza cruza medianoche.
    // Los estados se evalúan en el servicio, no se ocultan aquí.
    @Query("SELECT s FROM SolicitudEspacio s WHERE s.espacio.id = :espacioId " +
            "AND s.fechaEvento BETWEEN :desde AND :hasta " +
            "ORDER BY s.fechaEvento ASC, s.horaInicio ASC")
    List<SolicitudEspacio> buscarParaComprobarDisponibilidad(
            @Param("espacioId") Long espacioId,
            @Param("desde") LocalDate desde,
            @Param("hasta") LocalDate hasta);
}
