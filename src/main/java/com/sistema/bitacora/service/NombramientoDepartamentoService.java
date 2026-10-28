package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.NombramientoDepartamento;
import com.sistema.bitacora.repository.DepartamentoRepository;
import com.sistema.bitacora.repository.NombramientoDepartamentoRepository;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
@PreAuthorize("hasRole('SUPERVISOR')")
@Transactional(readOnly = true)
public class NombramientoDepartamentoService {

    private final NombramientoDepartamentoRepository
            nombramientoRepository;

    private final DepartamentoRepository departamentoRepository;

    public NombramientoDepartamentoService(
            NombramientoDepartamentoRepository nombramientoRepository,
            DepartamentoRepository departamentoRepository) {

        this.nombramientoRepository = nombramientoRepository;
        this.departamentoRepository = departamentoRepository;
    }

    public List<NombramientoDepartamento> listarAbiertos() {
        return nombramientoRepository
                .findByFechaFinIsNullOrderByDepartamento_NombreDepartamentoAsc();
    }

    public List<NombramientoDepartamento> historial(
            Long departamentoId) {

        validarDepartamento(departamentoId);

        return nombramientoRepository
                .findByDepartamento_IdOrderByFechaInicioDescIdDesc(
                        departamentoId
                );
    }

    private void validarDepartamento(Long departamentoId) {
        if (departamentoId == null || departamentoId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Identificador de departamento inválido"
            );
        }

        if (!departamentoRepository.existsById(departamentoId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "El departamento no existe"
            );
        }
    }

    public List<TitularVista> listarAbiertosParaVista() {
        return nombramientoRepository
                .findByFechaFinIsNullOrderByDepartamento_NombreDepartamentoAsc()
                .stream()
                .map(n -> new TitularVista(
                        n.getDepartamento().getNombreDepartamento(),
                        n.getUsuario().getNombreCompleto(),
                        n.getUsuario().getCorreoInstitucional(),
                        n.getFechaInicio()
                ))
                .toList();
    }

    public record TitularVista(
            String departamento,
            String nombre,
            String correo,
            LocalDateTime fechaInicio
    ) {
    }

}