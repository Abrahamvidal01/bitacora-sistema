package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Departamento;
import com.sistema.bitacora.entity.NombramientoDepartamento;
import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.repository.DepartamentoRepository;
import com.sistema.bitacora.repository.NombramientoDepartamentoRepository;
import com.sistema.bitacora.repository.UsuarioRepository;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
@PreAuthorize("hasRole('SUPERVISOR')")
@Transactional(readOnly = true)
public class NombramientoDepartamentoService {

    private final NombramientoDepartamentoRepository nombramientoRepository;
    private final DepartamentoRepository departamentoRepository;
    private final UsuarioRepository usuarioRepository;

    public NombramientoDepartamentoService(
            NombramientoDepartamentoRepository nombramientoRepository,
            DepartamentoRepository departamentoRepository,
            UsuarioRepository usuarioRepository) {

        this.nombramientoRepository = nombramientoRepository;
        this.departamentoRepository = departamentoRepository;
        this.usuarioRepository = usuarioRepository;
    }

    public List<NombramientoDepartamento> listarAbiertos() {
        return nombramientoRepository
                .findByFechaFinIsNullOrderByDepartamento_NombreDepartamentoAsc();
    }

    public List<NombramientoDepartamento> historial(Long departamentoId) {
        validarDepartamento(departamentoId);

        return nombramientoRepository
                .findByDepartamento_IdOrderByFechaInicioDescIdDesc(departamentoId);
    }

    public List<TitularVista> listarAbiertosParaVista() {
        return nombramientoRepository
                .findByFechaFinIsNullOrderByDepartamento_NombreDepartamentoAsc()
                .stream()
                .map(n -> new TitularVista(
                        n.getId(),
                        n.getDepartamento().getNombreDepartamento(),
                        n.getUsuario().getNombreCompleto(),
                        n.getUsuario().getCorreoInstitucional(),
                        n.getFechaInicio()
                ))
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long registrar(
            Long departamentoId,
            Long usuarioId,
            LocalDateTime fechaInicio) {

        validarId(departamentoId, "departamento");
        validarId(usuarioId, "usuario");

        if (fechaInicio == null) {
            throw error(HttpStatus.BAD_REQUEST,
                    "La fecha de inicio es obligatoria");
        }

        // Esta operación registra altas efectivas, no nombramientos programados.
        if (fechaInicio.isAfter(LocalDateTime.now())) {
            throw error(HttpStatus.BAD_REQUEST,
                    "La fecha de inicio no puede estar en el futuro");
        }

        Usuario supervisor = obtenerSupervisorActual();

        // Todas las altas de este servicio bloquean primero el departamento.
        Departamento departamento = departamentoRepository
                .buscarPorIdParaActualizar(departamentoId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND,
                        "El departamento no existe"));

        if (!Boolean.TRUE.equals(departamento.getActivo())) {
            throw error(HttpStatus.BAD_REQUEST,
                    "El departamento está desactivado");
        }

        if (nombramientoRepository
                .existsByDepartamento_IdAndFechaFinIsNull(departamentoId)) {
            throw error(HttpStatus.CONFLICT,
                    "El departamento ya tiene un nombramiento abierto. "
                            + "Primero debe finalizarse el anterior");
        }

        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND,
                        "El usuario no existe"));

        if (!Boolean.TRUE.equals(usuario.getActivo())) {
            throw error(HttpStatus.BAD_REQUEST,
                    "El usuario está desactivado");
        }

        if (usuario.getDepartamento() == null
                || !departamentoId.equals(usuario.getDepartamento().getId())) {
            throw error(HttpStatus.BAD_REQUEST,
                    "El usuario no pertenece al departamento seleccionado");
        }

        if (!tieneRol(usuario, "SOLICITANTE")) {
            throw error(HttpStatus.BAD_REQUEST,
                    "El usuario debe tener el rol SOLICITANTE");
        }

        // Un alta sin fecha final no puede solaparse con periodos anteriores.
        boolean solapaHistorial = nombramientoRepository
                .findByDepartamento_IdOrderByFechaInicioDescIdDesc(departamentoId)
                .stream()
                .anyMatch(n -> n.getFechaFin() == null
                        || n.getFechaFin().isAfter(fechaInicio));

        if (solapaHistorial) {
            throw error(HttpStatus.CONFLICT,
                    "La fecha de inicio se solapa con el historial del departamento");
        }

        NombramientoDepartamento nombramiento = new NombramientoDepartamento();
        nombramiento.setDepartamento(departamento);
        nombramiento.setUsuario(usuario);
        nombramiento.setFechaInicio(fechaInicio);
        nombramiento.setRegistradoPor(supervisor);

        return nombramientoRepository.saveAndFlush(nombramiento).getId();
    }

    private Usuario obtenerSupervisorActual() {
        Authentication authentication = SecurityContextHolder
                .getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw error(HttpStatus.UNAUTHORIZED,
                    "Debes iniciar sesión");
        }

        Usuario supervisor = usuarioRepository
                .findByCorreoInstitucional(authentication.getName())
                .orElseThrow(() -> error(HttpStatus.FORBIDDEN,
                        "La cuenta autenticada no está registrada"));

        if (!Boolean.TRUE.equals(supervisor.getActivo())
                || !tieneRol(supervisor, "SUPERVISOR")) {
            throw error(HttpStatus.FORBIDDEN,
                    "Se requiere una cuenta activa con rol SUPERVISOR");
        }

        return supervisor;
    }

    private boolean tieneRol(Usuario usuario, String nombreRol) {
        return usuario.getRoles() != null
                && usuario.getRoles().stream()
                .anyMatch(rol -> rol != null
                        && nombreRol.equals(rol.getNombreRol()));
    }

    private void validarDepartamento(Long departamentoId) {
        validarId(departamentoId, "departamento");

        if (!departamentoRepository.existsById(departamentoId)) {
            throw error(HttpStatus.NOT_FOUND,
                    "El departamento no existe");
        }
    }

    private void validarId(Long id, String entidad) {
        if (id == null || id <= 0) {
            throw error(HttpStatus.BAD_REQUEST,
                    "Identificador de " + entidad + " inválido");
        }
    }

    private ResponseStatusException error(HttpStatus estado, String mensaje) {
        return new ResponseStatusException(estado, mensaje);
    }

    public record TitularVista(
            Long id,
            String departamento,
            String nombre,
            String correo,
            LocalDateTime fechaInicio
    ) {
    }

    public OpcionesFormulario opcionesParaFormulario() {

        List<DepartamentoOpcion> departamentos = departamentoRepository
                .findAll()
                .stream()
                .filter(d -> Boolean.TRUE.equals(d.getActivo()))
                .sorted(java.util.Comparator.comparing(
                        Departamento::getNombreDepartamento
                ))
                .map(d -> new DepartamentoOpcion(
                        d.getId(),
                        d.getNombreDepartamento()
                ))
                .toList();

        List<UsuarioOpcion> usuarios = usuarioRepository
                .findAll()
                .stream()
                .filter(u -> Boolean.TRUE.equals(u.getActivo()))
                .filter(u -> tieneRol(u, "SOLICITANTE"))
                .filter(u -> u.getDepartamento() != null
                        && Boolean.TRUE.equals(
                        u.getDepartamento().getActivo()
                ))
                .sorted(java.util.Comparator.comparing(
                        Usuario::getNombreCompleto
                ))
                .map(u -> new UsuarioOpcion(
                        u.getId(),
                        u.getNombreCompleto()
                                + " — " + u.getCorreoInstitucional()
                                + " — "
                                + u.getDepartamento().getNombreDepartamento()
                ))
                .toList();

        return new OpcionesFormulario(departamentos, usuarios);
    }

    public record DepartamentoOpcion(
            Long id,
            String nombre
    ) {
    }

    public record UsuarioOpcion(
            Long id,
            String etiqueta
    ) {
    }

    public record OpcionesFormulario(
            List<DepartamentoOpcion> departamentos,
            List<UsuarioOpcion> usuarios
    ) {
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void finalizar(
            Long nombramientoId,
            LocalDateTime fechaFin,
            String motivoFin) {

        validarId(nombramientoId, "nombramiento");

        if (fechaFin == null) {
            throw error(HttpStatus.BAD_REQUEST,
                    "La fecha de finalización es obligatoria");
        }

        if (fechaFin.isAfter(LocalDateTime.now())) {
            throw error(HttpStatus.BAD_REQUEST,
                    "La finalización no puede estar en el futuro");
        }

        if (motivoFin == null || motivoFin.isBlank()
                || motivoFin.trim().length() > 255) {
            throw error(HttpStatus.BAD_REQUEST,
                    "El motivo es obligatorio y admite hasta 255 caracteres");
        }

        Usuario supervisor = obtenerSupervisorActual();

        Long departamentoId = nombramientoRepository
                .buscarDepartamentoId(nombramientoId)
                .orElseThrow(() -> error(
                        HttpStatus.NOT_FOUND,
                        "El nombramiento no existe"
                ));

        // Usa el mismo bloqueo por departamento que el registro de altas.
        departamentoRepository
                .buscarPorIdParaActualizar(departamentoId)
                .orElseThrow(() -> error(
                        HttpStatus.NOT_FOUND,
                        "El departamento no existe"
                ));

        NombramientoDepartamento nombramiento =
                nombramientoRepository.findById(nombramientoId)
                        .orElseThrow(() -> error(
                                HttpStatus.NOT_FOUND,
                                "El nombramiento no existe"
                        ));

        if (nombramiento.getFechaFin() != null) {
            throw error(HttpStatus.CONFLICT,
                    "El nombramiento ya está finalizado");
        }

        if (fechaFin.isBefore(nombramiento.getFechaInicio())) {
            throw error(HttpStatus.BAD_REQUEST,
                    "La finalización no puede ser anterior al inicio del cargo");
        }

        nombramiento.setFechaFin(fechaFin);
        nombramiento.setMotivoFin(motivoFin.trim());
        nombramiento.setFinalizadoPor(supervisor);

        nombramientoRepository.saveAndFlush(nombramiento);
    }


    public TitularVista obtenerAbiertoParaFinalizar(Long nombramientoId) {

        validarId(nombramientoId, "nombramiento");

        NombramientoDepartamento nombramiento =
                nombramientoRepository.findById(nombramientoId)
                        .orElseThrow(() -> error(
                                HttpStatus.NOT_FOUND,
                                "El nombramiento no existe"
                        ));

        if (nombramiento.getFechaFin() != null) {
            throw error(HttpStatus.CONFLICT,
                    "El nombramiento ya está finalizado");
        }

        return new TitularVista(
                nombramiento.getId(),
                nombramiento.getDepartamento().getNombreDepartamento(),
                nombramiento.getUsuario().getNombreCompleto(),
                nombramiento.getUsuario().getCorreoInstitucional(),
                nombramiento.getFechaInicio()
        );
    }


}
