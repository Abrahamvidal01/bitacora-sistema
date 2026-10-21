package com.sistema.bitacora.repository;

import com.sistema.bitacora.entity.InventarioEspacio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InventarioEspacioRepository
        extends JpaRepository<InventarioEspacio, Long> {

    List<InventarioEspacio> findByEspacio_IdOrderByNombreItemAsc(
            Long espacioId
    );

    Optional<InventarioEspacio> findByIdAndEspacio_Id(
            Long id,
            Long espacioId
    );
}
