package com.sistema.bitacora.repository;

import com.sistema.bitacora.entity.Departamento;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartamentoRepository
        extends JpaRepository<Departamento, Long> {
}
