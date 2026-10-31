package com.sistema.bitacora.repository;

import com.sistema.bitacora.entity.NombramientoDepartamento;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NombramientoDepartamentoRepository
        extends JpaRepository<NombramientoDepartamento, Long> {

    Optional<NombramientoDepartamento>
    findByDepartamento_IdAndFechaFinIsNull(Long departamentoId);

    boolean existsByDepartamento_IdAndFechaFinIsNull(
            Long departamentoId
    );

    List<NombramientoDepartamento>
    findByDepartamento_IdOrderByFechaInicioDescIdDesc(
            Long departamentoId
    );

    List<NombramientoDepartamento>
    findByFechaFinIsNullOrderByDepartamento_NombreDepartamentoAsc();

    @Query("""
            select n.departamento.id
            from NombramientoDepartamento n
            where n.id = :nombramientoId
            """)
    Optional<Long> buscarDepartamentoId(
            @Param("nombramientoId") Long nombramientoId
    );
}