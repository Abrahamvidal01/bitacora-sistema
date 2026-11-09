package com.sistema.bitacora.repository;

import com.sistema.bitacora.entity.SolicitudRecurso;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SolicitudRecursoRepository
        extends JpaRepository<SolicitudRecurso, Long> {

    List<SolicitudRecurso> findBySolicitudIdOrderByIdAsc(
            Long solicitudId
    );
}