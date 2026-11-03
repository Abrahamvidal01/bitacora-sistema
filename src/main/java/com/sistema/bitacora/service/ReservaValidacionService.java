package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Departamento;
import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.NombramientoDepartamento;
import com.sistema.bitacora.entity.SolicitudEspacio;
import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.repository.EspacioRepository;
import com.sistema.bitacora.repository.NombramientoDepartamentoRepository;
import com.sistema.bitacora.repository.SolicitudEspacioRepository;
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
import java.util.Locale;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SOLICITANTE')")
public class ReservaValidacionService {

    private final UsuarioRepository usuarioRepository;
    private final NombramientoDepartamentoRepository nombramientoRepository;
    private final SolicitudEspacioRepository solicitudEspacioRepository;
    private final EspacioRepository espacioRepository;
    private final Clock reloj;

    public ReservaValidacionService(
            UsuarioRepository usuarioRepository,
            NombramientoDepartamentoRepository nombramientoRepository,
            SolicitudEspacioRepository solicitudEspacioRepository,
            EspacioRepository espacioRepository,
            @Qualifier("relojReservas") Clock reloj) {
        this.usuarioRepository = usuarioRepository;
        this.nombramientoRepository = nombramientoRepository;
        this.solicitudEspacioRepository = solicitudEspacioRepository;
        this.espacioRepository = espacioRepository;
        this.reloj = reloj;
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResultadoValidacionInicial validarParaAdministrador(
            Long espacioId,
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

        return validar(espacioId, administrador, solicitante,
                fechaEvento, horaInicio, horaFin);
    }

    @PreAuthorize("hasRole('SOLICITANTE')")
    public ResultadoValidacionInicial validarParaSolicitante(
            Long espacioId,
            LocalDate fechaEvento,
            LocalTime horaInicio,
            LocalTime horaFin) {
        Usuario solicitante = obtenerActor("SOLICITANTE");
        return validar(espacioId, solicitante, solicitante,
                fechaEvento, horaInicio, horaFin);
    }

    private ResultadoValidacionInicial validar(
            Long espacioId,
            Usuario actor,
            Usuario solicitante,
            LocalDate fechaEvento,
            LocalTime horaInicio,
            LocalTime horaFin) {
        if (espacioId == null || espacioId <= 0) {
            throw error(HttpStatus.BAD_REQUEST,
                    "Selecciona un espacio válido");
        }
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
        if (inicioEvento.isBefore(ahora.plus(Duration.ofHours(72)))) {
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

        comprobarEspacioYLimpieza(espacioId, inicio, fin);

        return new ResultadoValidacionInicial(
                actor.getId(), solicitante.getId(), departamento.getId(),
                nombramiento.getId(), inicio, fin, reloj.getZone().getId());
    }

    private void comprobarEspacioYLimpieza(
            Long espacioId, LocalDateTime inicio, LocalDateTime fin) {
        Espacio espacio = espacioRepository.findById(espacioId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND,
                        "El espacio seleccionado no existe"));
        if (!Boolean.TRUE.equals(espacio.getActivo())) {
            throw error(HttpStatus.CONFLICT,
                    "El espacio seleccionado está desactivado");
        }
        if (!"DISPONIBLE".equals(normalizar(espacio.getEstadoActual()))) {
            throw error(HttpStatus.CONFLICT,
                    "El espacio no está habilitado para reservas. Revisa su estado o mantenimiento");
        }

        Integer minutos = espacio.getMinutosLimpieza();
        if (minutos == null || minutos <= 0) {
            throw error(HttpStatus.CONFLICT,
                    "El espacio no tiene una duración de limpieza válida. Revisa su configuración");
        }

        // Margen continuo después de cada evento. No programa personal de limpieza
        // ni permite aplazar este margen a la mañana siguiente.
        LocalDateTime finConLimpieza = fin.plusMinutes(minutos);
        LocalDate desde = inicio.minusMinutes(minutos).toLocalDate();
        LocalDate hasta = finConLimpieza.toLocalDate();

        for (SolicitudEspacio existente : solicitudEspacioRepository
                .buscarParaComprobarDisponibilidad(espacioId, desde, hasta)) {
            String estado = normalizar(existente.getEstatusSolicitud());
            // Únicos estados liberadores en esta comprobación.
            // Estados nulos o desconocidos se consideran bloqueantes por seguridad.
            if ("CANCELADA".equals(estado) || "RECHAZADA".equals(estado)) {
                continue;
            }
            if (existente.getFechaEvento() == null
                    || existente.getHoraInicio() == null
                    || existente.getHoraFin() == null
                    || !existente.getHoraFin().isAfter(existente.getHoraInicio())) {
                throw error(HttpStatus.CONFLICT,
                        "Hay una solicitud con horario inconsistente para este espacio. "
                                + "Debe revisarse antes de comprobar disponibilidad");
            }

            LocalDateTime inicioExistente = LocalDateTime.of(
                    existente.getFechaEvento(), existente.getHoraInicio());
            LocalDateTime finExistenteConLimpieza = LocalDateTime.of(
                            existente.getFechaEvento(), existente.getHoraFin())
                    .plusMinutes(minutos);

            // Intervalos [inicio, fin + limpieza): permite comenzar exactamente
            // cuando termina el margen, pero nunca dentro de él.
            if (inicio.isBefore(finExistenteConLimpieza)
                    && finConLimpieza.isAfter(inicioExistente)) {
                throw error(HttpStatus.CONFLICT,
                        "El horario se cruza con otra solicitud o con el margen de limpieza "
                                + "del espacio (" + minutos + " minutos después de cada evento). "
                                + "Elige otro horario");
            }
        }
    }

    private String normalizar(String valor) {
        return valor == null ? "" : valor.trim().toUpperCase(Locale.ROOT);
    }

    private Usuario obtenerActor(String rolNecesario) {
        Authentication authentication = SecurityContextHolder
                .getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw error(HttpStatus.UNAUTHORIZED, "Debes iniciar sesión");
        }
        Usuario actor = usuarioRepository
                .findByCorreoInstitucional(authentication.getName())
                .orElseThrow(() -> error(HttpStatus.FORBIDDEN,
                        "La cuenta autenticada no está registrada"));
        if (!Boolean.TRUE.equals(actor.getActivo()) || !tieneRol(actor, rolNecesario)) {
            throw error(HttpStatus.FORBIDDEN,
                    "La cuenta debe estar activa y tener el rol " + rolNecesario);
        }
        return actor;
    }

    private boolean tieneRol(Usuario usuario, String nombreRol) {
        return usuario.getRoles() != null
                && usuario.getRoles().stream().anyMatch(rol -> rol != null
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
            String zonaHoraria) {
    }
}
