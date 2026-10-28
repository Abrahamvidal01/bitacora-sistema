package com.sistema.bitacora.repository;

import com.sistema.bitacora.entity.SolicitudEspacio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SolicitudEspacioRepository
        extends JpaRepository<SolicitudEspacio, Long> {

    List<SolicitudEspacio>
    findByEspacio_IdAndFechaEventoOrderByHoraInicioAsc(
            Long espacioId,
            LocalDate fechaEvento
    );
}
