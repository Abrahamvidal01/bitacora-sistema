package com.sistema.bitacora.repository;

import com.sistema.bitacora.entity.Espacio;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // Usar únicamente dentro de la transacción de guardado.
    // No se utiliza mientras el usuario llena el formulario.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Espacio e WHERE e.id = :espacioId")
    Optional<Espacio> buscarPorIdParaReservar(
            @Param("espacioId") Long espacioId
    );
}