package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Departamento;
import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.NombramientoDepartamento;
import com.sistema.bitacora.entity.Rol;
import com.sistema.bitacora.entity.SolicitudEspacio;
import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.repository.EspacioRepository;
import com.sistema.bitacora.repository.NombramientoDepartamentoRepository;
import com.sistema.bitacora.repository.SolicitudEspacioRepository;
import com.sistema.bitacora.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Pruebas unitarias: no usan MariaDB.
// No prueban proxies de seguridad ni peticiones HTTP reales.
class ReservaUrgenciaValidacionServiceTest {

    private static final Long ESPACIO_ID = 2L;
    private static final String CORREO = "solicitante@ejemplo.test";

    // Reloj fijo: 3 de noviembre de 2026 a las 12:00 en México.
    private static final LocalDate HOY =
            LocalDate.of(2026, 11, 3);

    private static final LocalDate MANANA =
            LocalDate.of(2026, 11, 4);

    private static final LocalDate DIA_72_HORAS =
            LocalDate.of(2026, 11, 6);

    private UsuarioRepository usuarios;
    private NombramientoDepartamentoRepository nombramientos;
    private SolicitudEspacioRepository solicitudes;
    private EspacioRepository espacios;
    private ReservaValidacionService servicio;
    private Espacio espacio;

    @BeforeEach
    void preparar() {
        usuarios = mock(UsuarioRepository.class);
        nombramientos = mock(NombramientoDepartamentoRepository.class);
        solicitudes = mock(SolicitudEspacioRepository.class);
        espacios = mock(EspacioRepository.class);

        Clock reloj = Clock.fixed(
                Instant.parse("2026-11-03T18:00:00Z"),
                ZoneId.of("America/Mexico_City")
        );

        servicio = new ReservaValidacionService(
                usuarios, nombramientos, solicitudes, espacios, reloj
        );

        Usuario administrador = new Usuario();
        administrador.setId(1L);
        administrador.setActivo(true);
        administrador.setRoles(Set.of(rol("ADMINISTRADOR")));

        when(usuarios.findByCorreoInstitucional("admin@ejemplo.test"))
                .thenReturn(Optional.of(administrador));

        Departamento departamento = new Departamento();
        departamento.setId(1L);
        departamento.setActivo(true);

        Usuario solicitante = new Usuario();
        solicitante.setId(2L);
        solicitante.setActivo(true);
        solicitante.setRoles(Set.of(rol("SOLICITANTE")));
        solicitante.setDepartamento(departamento);

        when(usuarios.findByCorreoInstitucional(CORREO))
                .thenReturn(Optional.of(solicitante));

        NombramientoDepartamento nombramiento =
                new NombramientoDepartamento();

        nombramiento.setId(3L);
        nombramiento.setDepartamento(departamento);
        nombramiento.setUsuario(solicitante);
        nombramiento.setFechaInicio(
                LocalDateTime.of(2026, 6, 10, 13, 15)
        );

        when(nombramientos.findByDepartamento_IdAndFechaFinIsNull(1L))
                .thenReturn(Optional.of(nombramiento));

        espacio = new Espacio();
        espacio.setId(ESPACIO_ID);
        espacio.setActivo(true);
        espacio.setEstadoActual("DISPONIBLE");
        espacio.setMinutosLimpieza(30);

        when(espacios.findById(ESPACIO_ID))
                .thenReturn(Optional.of(espacio));

        agenda(List.of());
        autenticar("admin@ejemplo.test", "ADMINISTRADOR");
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registroNormalRechazaUnMinutoAntesDe72Horas() {
        var error = assertThrows(
                ResponseStatusException.class,
                () -> normal(DIA_72_HORAS, "11:59", "13:00")
        );

        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void registroNormalAceptaExactamente72Horas() {
        assertDoesNotThrow(() ->
                normal(DIA_72_HORAS, "12:00", "13:00")
        );
    }

    @Test
    void solicitanteNoPuedeReservarConSolo24Horas() {
        autenticar(CORREO, "SOLICITANTE");

        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validarParaSolicitante(
                        ESPACIO_ID,
                        MANANA,
                        LocalTime.of(12, 0),
                        LocalTime.of(13, 0)
                )
        );

        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void urgenciaInternaAceptaExactamente24HorasYNormalizaMotivo() {
        var resultado = urgente(
                MANANA, "12:00", "13:00",
                "  El titular no pudo ingresar  ",
                "   ", false
        );

        assertEquals(
                "El titular no pudo ingresar",
                resultado.motivoUrgencia()
        );

        assertEquals(
                ReservaValidacionService.ModalidadUrgencia.SOLO_INVENTARIO,
                resultado.modalidad()
        );

        assertEquals(24, resultado.modalidad().horasMinimas());
        assertFalse(resultado.modalidad().requiereRevisionSupervisor());
        assertEquals(1L, resultado.validacion().registradoPorUsuarioId());
        assertEquals(2L, resultado.validacion().solicitanteId());

        verify(solicitudes, never()).save(any(SolicitudEspacio.class));
        verify(solicitudes, never())
                .saveAndFlush(any(SolicitudEspacio.class));
    }

    @Test
    void urgenciaInternaRechazaUnMinutoAntesDe24Horas() {
        rechazar(
                MANANA, "11:59", "13:00",
                "Evento olvidado", "", false, 400
        );
    }

    @Test
    void urgenciaInternaRechazaUnaSolicitudParaDentroDeDosHoras() {
        rechazar(
                HOY, "14:00", "15:00",
                "Evento olvidado", "", false, 400
        );
    }

    @Test
    void urgenciaInternaAceptaMasDe24Horas() {
        assertDoesNotThrow(() -> urgente(
                MANANA, "13:00", "14:00",
                "Evento olvidado", "", false
        ));
    }

    @Test
    void urgenciaConRecursosExternosRechazaMenosDe72Horas() {
        rechazar(
                DIA_72_HORAS, "11:59", "13:00",
                "Captura administrativa",
                "Servicio externo de sonido",
                true,
                400
        );
    }

    @Test
    void urgenciaConRequisicionAcepta72HorasYRequiereSupervisor() {
        var resultado = urgente(
                DIA_72_HORAS, "12:00", "13:00",
                "Captura administrativa",
                "Servicio externo de sonido",
                true
        );

        assertEquals(
                ReservaValidacionService.ModalidadUrgencia.CON_REQUISICION,
                resultado.modalidad()
        );

        assertEquals(72, resultado.modalidad().horasMinimas());
        assertTrue(resultado.modalidad().requiereRevisionSupervisor());
    }

    @Test
    void adjuntarRequisicionExige72HorasAunqueNoHayaDescripcionExterna() {
        rechazar(
                MANANA, "12:00", "13:00",
                "Captura administrativa", "", true, 400
        );
    }

    @Test
    void omitirLaRequisicionNoReduceElPlazoDeLosRecursosExternos() {
        rechazar(
                MANANA, "12:00", "13:00",
                "Captura administrativa",
                "Mesas fuera del inventario escolar",
                false,
                400
        );
    }

    @Test
    void requisicionSinDescripcionTambienSeleccionaModalidadDe72Horas() {
        var resultado = urgente(
                DIA_72_HORAS, "12:00", "13:00",
                "Captura administrativa", "", true
        );

        assertEquals(
                ReservaValidacionService.ModalidadUrgencia.CON_REQUISICION,
                resultado.modalidad()
        );

        assertTrue(resultado.modalidad().requiereRevisionSupervisor());
    }

    @Test
    void rechazaMotivoNulo() {
        rechazar(
                MANANA, "12:00", "13:00",
                null, "", false, 400
        );
    }

    @Test
    void rechazaMotivoVacio() {
        rechazar(
                MANANA, "12:00", "13:00",
                "   ", "", false, 400
        );
    }

    @Test
    void rechazaMotivoMayorDe255Caracteres() {
        rechazar(
                MANANA, "12:00", "13:00",
                "a".repeat(256), "", false, 400
        );
    }

    @Test
    void rechazaUnEventoQueYaInicio() {
        rechazar(
                HOY, "11:00", "13:00",
                "Evento olvidado", "", false, 400
        );
    }

    @Test
    void rechazaInicioExactamenteEnLaHoraActual() {
        rechazar(
                HOY, "12:00", "13:00",
                "Evento olvidado", "", false, 400
        );
    }

    @Test
    void solicitanteNoPuedeUsarElMetodoAdministrativoDeUrgencia() {
        autenticar(CORREO, "SOLICITANTE");

        rechazar(
                MANANA, "12:00", "13:00",
                "Evento olvidado", "", false, 403
        );
    }

    @Test
    void urgenciaNoPermiteEspacioEnMantenimiento() {
        espacio.setEstadoActual("MANTENIMIENTO");

        rechazar(
                MANANA, "12:00", "13:00",
                "Evento olvidado", "", false, 409
        );
    }

    @Test
    void urgenciaNoPermiteCruzarOtraReserva() {
        agenda(List.of(evento("12:00", "13:00")));

        rechazar(
                MANANA, "12:30", "13:30",
                "Evento olvidado", "", false, 409
        );
    }

    @Test
    void urgenciaRespetaLaLimpiezaDeLaReservaAnterior() {
        agenda(List.of(evento("12:00", "13:00")));

        rechazar(
                MANANA, "13:15", "14:00",
                "Evento olvidado", "", false, 409
        );

        assertDoesNotThrow(() -> urgente(
                MANANA, "13:30", "14:30",
                "Evento olvidado", "", false
        ));
    }

    private ReservaValidacionService.ResultadoValidacionInicial normal(
            LocalDate fecha,
            String inicio,
            String fin) {

        return servicio.validarParaAdministrador(
                ESPACIO_ID,
                CORREO,
                fecha,
                LocalTime.parse(inicio),
                LocalTime.parse(fin)
        );
    }

    private ReservaValidacionService.ResultadoUrgenciaAdministrativa urgente(
            LocalDate fecha,
            String inicio,
            String fin,
            String motivo,
            String externos,
            boolean requisicion) {

        return servicio.validarUrgenciaParaAdministrador(
                ESPACIO_ID,
                CORREO,
                fecha,
                LocalTime.parse(inicio),
                LocalTime.parse(fin),
                motivo,
                externos,
                requisicion
        );
    }

    private void rechazar(
            LocalDate fecha,
            String inicio,
            String fin,
            String motivo,
            String externos,
            boolean requisicion,
            int codigoEsperado) {

        var error = assertThrows(
                ResponseStatusException.class,
                () -> urgente(
                        fecha, inicio, fin,
                        motivo, externos, requisicion
                )
        );

        assertEquals(codigoEsperado, error.getStatusCode().value());
    }

    private void agenda(List<SolicitudEspacio> eventos) {
        when(solicitudes.buscarParaComprobarDisponibilidad(
                eq(ESPACIO_ID),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(eventos);
    }

    private SolicitudEspacio evento(String inicio, String fin) {
        SolicitudEspacio evento = new SolicitudEspacio();
        evento.setEspacio(espacio);
        evento.setFechaEvento(MANANA);
        evento.setHoraInicio(LocalTime.parse(inicio));
        evento.setHoraFin(LocalTime.parse(fin));
        evento.setEstatusSolicitud("APROBADA");
        return evento;
    }

    private void autenticar(String correo, String nombreRol) {
        var autenticacion = new UsernamePasswordAuthenticationToken(
                correo,
                "no-se-usa",
                List.of(new SimpleGrantedAuthority("ROLE_" + nombreRol))
        );

        SecurityContextHolder.getContext()
                .setAuthentication(autenticacion);
    }

    private Rol rol(String nombre) {
        Rol rol = new Rol();
        rol.setNombreRol(nombre);
        return rol;
    }
}
