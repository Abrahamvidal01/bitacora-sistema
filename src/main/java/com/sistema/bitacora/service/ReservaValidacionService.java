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

    // Registro normal: conserva las 72 horas.
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResultadoValidacionInicial validarParaAdministrador(
            Long espacioId,
            String correoSolicitante,
            LocalDate fechaEvento,
            LocalTime horaInicio,
            LocalTime horaFin) {

        Usuario administrador = obtenerActor("ADMINISTRADOR");
        Usuario solicitante = obtenerSolicitante(correoSolicitante);

        return validar(
                espacioId, administrador, solicitante,
                fechaEvento, horaInicio, horaFin, 72
        );
    }

    /**
     * Comprueba una modalidad administrativa de urgencia.
     *
     * No guarda reservas ni registra todavía la autorización.
     * No valida los PDF, su obligatoriedad, inventario ni cupos:
     * esas comprobaciones siguen en sus servicios correspondientes.
     *
     * tieneRequisicion debe calcularse en el servidor
     * a partir de los archivos recibidos, no de una casilla.
     */
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResultadoUrgenciaAdministrativa validarUrgenciaParaAdministrador(
            Long espacioId,
            String correoSolicitante,
            LocalDate fechaEvento,
            LocalTime horaInicio,
            LocalTime horaFin,
            String motivoUrgencia,
            String recursosExternos,
            boolean tieneRequisicion) {

        Usuario administrador = obtenerActor("ADMINISTRADOR");

        String motivoNormalizado = validarMotivoUrgencia(motivoUrgencia);

        boolean solicitaExternos =
                recursosExternos != null && !recursosExternos.isBlank();

        // Tener recursos externos exige el modo de 72 horas,
        // aunque todavía no se haya adjuntado la requisición.
        // Adjuntar una requisición también selecciona ese modo.
        ModalidadUrgencia modalidad =
                solicitaExternos || tieneRequisicion
                        ? ModalidadUrgencia.CON_REQUISICION
                        : ModalidadUrgencia.SOLO_INVENTARIO;

        Usuario solicitante = obtenerSolicitante(correoSolicitante);

        ResultadoValidacionInicial resultado = validar(
                espacioId, administrador, solicitante,
                fechaEvento, horaInicio, horaFin,
                modalidad.horasMinimas()
        );

        return new ResultadoUrgenciaAdministrativa(
                resultado,
                motivoNormalizado,
                modalidad
        );
    }

    @PreAuthorize("hasRole('SOLICITANTE')")
    public ResultadoValidacionInicial validarParaSolicitante(
            Long espacioId,
            LocalDate fechaEvento,
            LocalTime horaInicio,
            LocalTime horaFin) {

        Usuario solicitante = obtenerActor("SOLICITANTE");

        return validar(
                espacioId, solicitante, solicitante,
                fechaEvento, horaInicio, horaFin, 72
        );
    }

    private Usuario obtenerSolicitante(String correo) {
        if (correo == null || correo.isBlank()
                || correo.trim().length() > 100) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Indica el correo del solicitante registrado"
            );
        }

        return usuarioRepository
                .findByCorreoInstitucional(correo.trim())
                .orElseThrow(() -> error(
                        HttpStatus.NOT_FOUND,
                        "No existe una cuenta con ese correo"
                ));
    }

    private String validarMotivoUrgencia(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Indica el motivo de la urgencia administrativa"
            );
        }

        String normalizado = motivo.strip();

        if (normalizado.length() > 255) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "El motivo de urgencia admite hasta 255 caracteres"
            );
        }

        return normalizado;
    }

    private ResultadoValidacionInicial validar(
            Long espacioId,
            Usuario actor,
            Usuario solicitante,
            LocalDate fechaEvento,
            LocalTime horaInicio,
            LocalTime horaFin,
            int horasMinimas) {

        if (espacioId == null || espacioId <= 0) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Selecciona un espacio válido"
            );
        }

        if (fechaEvento == null || horaInicio == null || horaFin == null) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "La fecha y las horas de inicio y fin son obligatorias"
            );
        }

        if (!horaFin.isAfter(horaInicio)) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "La hora de fin debe ser posterior al inicio. "
                            + "El evento debe terminar el mismo día"
            );
        }

        Instant ahora = reloj.instant();
        LocalDateTime inicio = LocalDateTime.of(fechaEvento, horaInicio);
        LocalDateTime fin = LocalDateTime.of(fechaEvento, horaFin);
        Instant inicioEvento = inicio.atZone(reloj.getZone()).toInstant();

        if (!inicioEvento.isAfter(ahora)) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "El inicio del evento debe ser posterior "
                            + "a la fecha y hora actuales"
            );
        }

        if (inicioEvento.isBefore(
                ahora.plus(Duration.ofHours(horasMinimas)))) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Esta modalidad requiere al menos "
                            + horasMinimas
                            + " horas completas de anticipación "
                            + "al inicio del evento"
            );
        }

        if (!Boolean.TRUE.equals(solicitante.getActivo())) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "La cuenta del solicitante está desactivada"
            );
        }

        if (!tieneRol(solicitante, "SOLICITANTE")) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "La cuenta debe tener el rol SOLICITANTE"
            );
        }

        Departamento departamento = solicitante.getDepartamento();

        if (departamento == null
                || !Boolean.TRUE.equals(departamento.getActivo())) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "El solicitante debe pertenecer a un departamento activo"
            );
        }

        NombramientoDepartamento nombramiento = nombramientoRepository
                .findByDepartamento_IdAndFechaFinIsNull(departamento.getId())
                .orElseThrow(() -> error(
                        HttpStatus.CONFLICT,
                        "El departamento no tiene un nombramiento abierto"
                ));

        if (!solicitante.getId().equals(nombramiento.getUsuario().getId())) {
            throw error(
                    HttpStatus.CONFLICT,
                    "La cuenta seleccionada no corresponde "
                            + "al titular actual del departamento"
            );
        }

        LocalDateTime ahoraLocal =
                LocalDateTime.ofInstant(ahora, reloj.getZone());

        if (nombramiento.getFechaInicio().isAfter(ahoraLocal)) {
            throw error(
                    HttpStatus.CONFLICT,
                    "El nombramiento todavía no ha iniciado"
            );
        }

        comprobarEspacioYLimpieza(espacioId, inicio, fin);

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

    private void comprobarEspacioYLimpieza(
            Long espacioId,
            LocalDateTime inicio,
            LocalDateTime fin) {

        Espacio espacio = espacioRepository.findById(espacioId)
                .orElseThrow(() -> error(
                        HttpStatus.NOT_FOUND,
                        "El espacio seleccionado no existe"
                ));

        if (!Boolean.TRUE.equals(espacio.getActivo())) {
            throw error(
                    HttpStatus.CONFLICT,
                    "El espacio seleccionado está desactivado"
            );
        }

        if (!"DISPONIBLE".equals(normalizar(espacio.getEstadoActual()))) {
            throw error(
                    HttpStatus.CONFLICT,
                    "El espacio no está habilitado para reservas. "
                            + "Revisa su estado o mantenimiento"
            );
        }

        Integer minutos = espacio.getMinutosLimpieza();

        if (minutos == null || minutos <= 0) {
            throw error(
                    HttpStatus.CONFLICT,
                    "El espacio no tiene una duración de limpieza válida. "
                            + "Revisa su configuración"
            );
        }

        LocalDateTime finConLimpieza = fin.plusMinutes(minutos);
        LocalDate desde = inicio.minusMinutes(minutos).toLocalDate();
        LocalDate hasta = finConLimpieza.toLocalDate();

        for (SolicitudEspacio existente : solicitudEspacioRepository
                .buscarParaComprobarDisponibilidad(espacioId, desde, hasta)) {

            String estado = normalizar(existente.getEstatusSolicitud());

            // Estados desconocidos siguen bloqueando.
            if ("CANCELADA".equals(estado) || "RECHAZADA".equals(estado)) {
                continue;
            }

            if (existente.getFechaEvento() == null
                    || existente.getHoraInicio() == null
                    || existente.getHoraFin() == null
                    || !existente.getHoraFin()
                    .isAfter(existente.getHoraInicio())) {

                throw error(
                        HttpStatus.CONFLICT,
                        "Hay una solicitud con horario inconsistente "
                                + "para este espacio. Debe revisarse antes "
                                + "de comprobar disponibilidad"
                );
            }

            LocalDateTime inicioExistente = LocalDateTime.of(
                    existente.getFechaEvento(),
                    existente.getHoraInicio()
            );

            LocalDateTime finExistenteConLimpieza = LocalDateTime.of(
                    existente.getFechaEvento(),
                    existente.getHoraFin()
            ).plusMinutes(minutos);

            if (inicio.isBefore(finExistenteConLimpieza)
                    && finConLimpieza.isAfter(inicioExistente)) {
                throw error(
                        HttpStatus.CONFLICT,
                        "El horario se cruza con otra solicitud "
                                + "o con el margen de limpieza del espacio ("
                                + minutos + " minutos después de cada evento). "
                                + "Elige otro horario"
                );
            }
        }
    }

    private String normalizar(String valor) {
        return valor == null
                ? ""
                : valor.trim().toUpperCase(Locale.ROOT);
    }

    private Usuario obtenerActor(String rolNecesario) {
        Authentication autenticacion = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (autenticacion == null || !autenticacion.isAuthenticated()) {
            throw error(
                    HttpStatus.UNAUTHORIZED,
                    "Debes iniciar sesión"
            );
        }

        Usuario actor = usuarioRepository
                .findByCorreoInstitucional(autenticacion.getName())
                .orElseThrow(() -> error(
                        HttpStatus.FORBIDDEN,
                        "La cuenta autenticada no está registrada"
                ));

        if (!Boolean.TRUE.equals(actor.getActivo())
                || !tieneRol(actor, rolNecesario)) {
            throw error(
                    HttpStatus.FORBIDDEN,
                    "La cuenta debe estar activa y tener el rol "
                            + rolNecesario
            );
        }

        return actor;
    }

    private boolean tieneRol(Usuario usuario, String nombreRol) {
        return usuario.getRoles() != null
                && usuario.getRoles().stream().anyMatch(
                rol -> rol != null
                        && nombreRol.equals(rol.getNombreRol())
        );
    }

    private ResponseStatusException error(
            HttpStatus estado,
            String mensaje) {
        return new ResponseStatusException(estado, mensaje);
    }

    public enum ModalidadUrgencia {
        SOLO_INVENTARIO(24, false),
        CON_REQUISICION(72, true);

        private final int horasMinimas;
        private final boolean requiereRevisionSupervisor;

        ModalidadUrgencia(
                int horasMinimas,
                boolean requiereRevisionSupervisor) {

            this.horasMinimas = horasMinimas;
            this.requiereRevisionSupervisor = requiereRevisionSupervisor;
        }

        public int horasMinimas() {
            return horasMinimas;
        }

        public boolean requiereRevisionSupervisor() {
            return requiereRevisionSupervisor;
        }
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

    public record ResultadoUrgenciaAdministrativa(
            ResultadoValidacionInicial validacion,
            String motivoUrgencia,
            ModalidadUrgencia modalidad) {
    }
}