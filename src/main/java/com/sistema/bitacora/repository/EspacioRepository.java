package com.sistema.bitacora.repository;

import com.sistema.bitacora.entity.Espacio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EspacioRepository
        extends JpaRepository<Espacio, Long> {

    List<Espacio> findAllByOrderByNombreEspacioAsc();

    List<Espacio> findByActivoTrueOrderByNombreEspacioAsc();

    Optional<Espacio> findByNombreEspacioIgnoreCase(
            String nombreEspacio
    );

    boolean existsByNombreEspacioIgnoreCaseAndIdNot(
            String nombreEspacio,
            Long id
    );
}
