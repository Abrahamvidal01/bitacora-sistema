package com.sistema.bitacora.repository;

import com.sistema.bitacora.entity.Departamento;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DepartamentoRepository
        extends JpaRepository<Departamento, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Departamento d where d.id = :id")
    Optional<Departamento> buscarPorIdParaActualizar(
            @Param("id") Long id
    );
}