package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Departamento;
import com.sistema.bitacora.entity.NombramientoDepartamento;
import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.repository.NombramientoDepartamentoRepository;
import com.sistema.bitacora.repository.UsuarioRepository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SOLICITANTE')")
public class ReservaValidacionService {

    private final UsuarioRepository usuarioRepository;
    private final NombramientoDepartamentoRepository nombramientoRepository;
    private final Clock reloj;

    public ReservaValidacionService(
            UsuarioRepository usuarioRepository,
            NombramientoDepartamentoRepository nombramientoRepository,
            @Qualifier("relojReservas") Clock reloj) {

        this.usuarioRepository = usuarioRepository;
        this.nombramientoRepository = nombramientoRepository;
        this.reloj = reloj;
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResultadoValidacionInicial validarParaAdministrador(
            String correoSolicitante,
            LocalDate fechaEvento,
            LocalTime horaInicio,
            LocalTime horaFin) {

        Usuario administrador = obtenerActor("ADMINISTRADOR");

        if (correoSolicitante == null || correoSolicitante.isBlank()
                || correoSolicitante.trim().length() > 100) {
            throw error(HttpStatus.BAD_REQUEST,
                    "Indica el correo del solicitante registrado");
        }

        Usuario solicitante = usuarioRepository
                .findByCorreoInstitucional(correoSolicitante.trim())
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND,
                        "No existe una cuenta con ese correo"));

        return validar(administrador, solicitante,
                fechaEvento, horaInicio, horaFin);
    }

    @PreAuthorize("hasRole('SOLICITANTE')")
    public ResultadoValidacionInicial validarParaSolicitante(
            LocalDate fechaEvento,
            LocalTime horaInicio,
            LocalTime horaFin) {

        // El solicitante se obtiene de la sesión, no de un ID o correo del formulario.
        Usuario solicitante = obtenerActor("SOLICITANTE");

        return validar(solicitante, solicitante,
                fechaEvento, horaInicio, horaFin);
    }

    private ResultadoValidacionInicial validar(
            Usuario actor,
            Usuario solicitante,
            LocalDate fechaEvento,
            LocalTime horaInicio,
            LocalTime horaFin) {

        if (fechaEvento == null || horaInicio == null || horaFin == null) {
            throw error(HttpStatus.BAD_REQUEST,
                    "La fecha y las horas de inicio y fin son obligatorias");
        }

        if (!horaFin.isAfter(horaInicio)) {
            throw error(HttpStatus.BAD_REQUEST,
                    "La hora de fin debe ser posterior al inicio. "
                            + "El evento debe terminar el mismo día");
        }

        Instant ahora = reloj.instant();
        LocalDateTime inicio = LocalDateTime.of(fechaEvento, horaInicio);
        LocalDateTime fin = LocalDateTime.of(fechaEvento, horaFin);

        Instant inicioEvento = inicio.atZone(reloj.getZone()).toInstant();
        Instant minimoPermitido = ahora.plus(Duration.ofHours(72));

        if (inicioEvento.isBefore(minimoPermitido)) {
            throw error(HttpStatus.BAD_REQUEST,
                    "La reserva debe solicitarse con al menos 72 horas completas "
                            + "de anticipación al inicio del evento");
        }

        if (!Boolean.TRUE.equals(solicitante.getActivo())) {
            throw error(HttpStatus.BAD_REQUEST,
                    "La cuenta del solicitante está desactivada");
        }

        if (!tieneRol(solicitante, "SOLICITANTE")) {
            throw error(HttpStatus.BAD_REQUEST,
                    "La cuenta debe tener el rol SOLICITANTE");
        }

        Departamento departamento = solicitante.getDepartamento();
        if (departamento == null || !Boolean.TRUE.equals(departamento.getActivo())) {
            throw error(HttpStatus.BAD_REQUEST,
                    "El solicitante debe pertenecer a un departamento activo");
        }

        NombramientoDepartamento nombramiento = nombramientoRepository
                .findByDepartamento_IdAndFechaFinIsNull(departamento.getId())
                .orElseThrow(() -> error(HttpStatus.CONFLICT,
                        "El departamento no tiene un nombramiento abierto"));

        if (!solicitante.getId().equals(nombramiento.getUsuario().getId())) {
            throw error(HttpStatus.CONFLICT,
                    "La cuenta seleccionada no corresponde al titular actual del departamento");
        }

        LocalDateTime ahoraLocal = LocalDateTime.ofInstant(ahora, reloj.getZone());
        if (nombramiento.getFechaInicio().isAfter(ahoraLocal)) {
            throw error(HttpStatus.CONFLICT,
                    "El nombramiento todavía no ha iniciado");
        }

        // Este resultado NO aprueba ni guarda una reserva.
        // No comprueba disponibilidad del espacio, recursos, documentos ni cupos.
        return new ResultadoValidacionInicial(
                actor.getId(),
                solicitante.getId(),
                departamento.getId(),
                nombramiento.getId(),
                inicio,
                fin,
                reloj.getZone().getId()
        );
    }

    private Usuario obtenerActor(String rolNecesario) {
        Authentication authentication = SecurityContextHolder
                .getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw error(HttpStatus.UNAUTHORIZED,
                    "Debes iniciar sesión");
        }

        Usuario actor = usuarioRepository
                .findByCorreoInstitucional(authentication.getName())
                .orElseThrow(() -> error(HttpStatus.FORBIDDEN,
                        "La cuenta autenticada no está registrada"));

        if (!Boolean.TRUE.equals(actor.getActivo())
                || !tieneRol(actor, rolNecesario)) {
            throw error(HttpStatus.FORBIDDEN,
                    "La cuenta debe estar activa y tener el rol " + rolNecesario);
        }

        return actor;
    }

    private boolean tieneRol(Usuario usuario, String nombreRol) {
        return usuario.getRoles() != null
                && usuario.getRoles().stream()
                .anyMatch(rol -> rol != null
                        && nombreRol.equals(rol.getNombreRol()));
    }

    private ResponseStatusException error(HttpStatus estado, String mensaje) {
        return new ResponseStatusException(estado, mensaje);
    }

    public record ResultadoValidacionInicial(
            Long registradoPorUsuarioId,
            Long solicitanteId,
            Long departamentoId,
            Long nombramientoId,
            LocalDateTime inicio,
            LocalDateTime fin,
            String zonaHoraria
    ) {
    }
}
