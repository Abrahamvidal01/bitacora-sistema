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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Prueba unitaria: NO inicia Spring, NO usa MySQL y NO prueba los proxies
// de autorización, las consultas JPQL reales ni la concurrencia del guardado.
class ReservaValidacionServiceTest {

    private static final Long ESPACIO_ID = 2L;
    private static final String CORREO = "solicitante@ejemplo.test";
    private static final LocalDate FECHA = LocalDate.of(2026, 11, 10);

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

        Clock reloj = Clock.fixed(Instant.parse("2026-11-03T18:00:00Z"),
                ZoneId.of("America/Mexico_City"));
        servicio = new ReservaValidacionService(
                usuarios, nombramientos, solicitudes, espacios, reloj);

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

        NombramientoDepartamento nombramiento = new NombramientoDepartamento();
        nombramiento.setId(3L);
        nombramiento.setUsuario(solicitante);
        nombramiento.setDepartamento(departamento);
        nombramiento.setFechaInicio(LocalDateTime.of(2026, 6, 10, 13, 15));
        when(nombramientos.findByDepartamento_IdAndFechaFinIsNull(1L))
                .thenReturn(Optional.of(nombramiento));

        espacio = new Espacio();
        espacio.setId(ESPACIO_ID);
        espacio.setNombreEspacio("Sala de prueba");
        espacio.setActivo(true);
        espacio.setEstadoActual("DISPONIBLE");
        espacio.setMinutosLimpieza(30);
        when(espacios.findById(ESPACIO_ID)).thenReturn(Optional.of(espacio));
        agenda(List.of());

        var autenticacion = new UsernamePasswordAuthenticationToken(
                "admin@ejemplo.test", "no-se-usa",
                List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR")));
        SecurityContextHolder.getContext().setAuthentication(autenticacion);
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void agendaVaciaSuperaComprobacionSinGuardar() {
        var resultado = assertDoesNotThrow(() -> comprobar(FECHA, "10:00", "12:00"));
        assertEquals(FECHA.atTime(10, 0), resultado.inicio());
        verify(solicitudes, never()).save(any(SolicitudEspacio.class));
        verify(solicitudes, never()).saveAndFlush(any(SolicitudEspacio.class));
    }

    @Test
    void rechazaCruceDentroDelEvento() {
        eventoHabitual();
        rechazar(FECHA, "11:00", "13:00");
    }

    @Test
    void rechazaEventoConMismoHorario() {
        eventoHabitual();
        rechazar(FECHA, "10:00", "12:00");
    }

    @Test
    void rechazaInicioDentroDeLimpiezaAnterior() {
        eventoHabitual();
        rechazar(FECHA, "12:15", "13:00");
    }

    @Test
    void aceptaInicioExactamenteAlTerminarLimpieza() {
        eventoHabitual();
        assertDoesNotThrow(() -> comprobar(FECHA, "12:30", "13:30"));
    }

    @Test
    void rechazaSiLimpiezaDelNuevoEventoInvadeElExistente() {
        eventoHabitual();
        rechazar(FECHA, "09:00", "10:00");
    }

    @Test
    void aceptaSiLimpiezaDelNuevoTerminaJustoAlInicioExistente() {
        eventoHabitual();
        assertDoesNotThrow(() -> comprobar(FECHA, "08:30", "09:30"));
    }

    @Test
    void revisaLimpiezaDelDiaAnterior() {
        agenda(List.of(evento(FECHA, "23:00", "23:50", "APROBADA")));
        LocalDate siguiente = FECHA.plusDays(1);
        rechazar(siguiente, "00:10", "01:00");
        verify(solicitudes).buscarParaComprobarDisponibilidad(
                ESPACIO_ID, FECHA, siguiente);
    }

    @Test
    void aceptaCuandoTerminaLaLimpiezaQueCruzaMedianoche() {
        agenda(List.of(evento(FECHA, "23:00", "23:50", "APROBADA")));
        assertDoesNotThrow(() -> comprobar(FECHA.plusDays(1), "00:20", "01:00"));
    }

    @Test
    void solicitudPendienteTambienBloquea() {
        agenda(List.of(evento(FECHA, "10:00", "12:00", "PENDIENTE")));
        rechazar(FECHA, "11:00", "13:00");
    }

    @Test
    void canceladaNoBloqueaEnLaPoliticaActualDelServicio() {
        agenda(List.of(evento(FECHA, "10:00", "12:00", "CANCELADA")));
        assertDoesNotThrow(() -> comprobar(FECHA, "11:00", "13:00"));
    }

    @Test
    void estadoDesconocidoNoLiberaElHorario() {
        agenda(List.of(evento(FECHA, "10:00", "12:00", "ESTADO_NO_DEFINIDO")));
        rechazar(FECHA, "11:00", "13:00");
    }

    @Test
    void rechazaEspacioEnMantenimiento() {
        espacio.setEstadoActual("MANTENIMIENTO");
        ResponseStatusException error = rechazar(FECHA, "10:00", "12:00");
        assertTrue(error.getReason().contains("mantenimiento"));
    }

    @Test
    void rechazaEspacioDesactivado() {
        espacio.setActivo(false);
        rechazar(FECHA, "10:00", "12:00");
    }

    @Test
    void usaDuracionConfiguradaDe120Minutos() {
        espacio.setMinutosLimpieza(120);
        eventoHabitual();
        rechazar(FECHA, "13:30", "14:30");
        assertDoesNotThrow(() -> comprobar(FECHA, "14:00", "15:00"));
    }

    private void eventoHabitual() {
        agenda(List.of(evento(FECHA, "10:00", "12:00", "APROBADA")));
    }

    private void agenda(List<SolicitudEspacio> eventos) {
        when(solicitudes.buscarParaComprobarDisponibilidad(
                eq(ESPACIO_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(eventos);
    }

    private SolicitudEspacio evento(LocalDate fecha, String inicio, String fin, String estado) {
        SolicitudEspacio evento = new SolicitudEspacio();
        evento.setEspacio(espacio);
        evento.setFechaEvento(fecha);
        evento.setHoraInicio(LocalTime.parse(inicio));
        evento.setHoraFin(LocalTime.parse(fin));
        evento.setEstatusSolicitud(estado);
        return evento;
    }

    private ReservaValidacionService.ResultadoValidacionInicial comprobar(
            LocalDate fecha, String inicio, String fin) {
        return servicio.validarParaAdministrador(ESPACIO_ID, CORREO,
                fecha, LocalTime.parse(inicio), LocalTime.parse(fin));
    }

    private ResponseStatusException rechazar(LocalDate fecha, String inicio, String fin) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> comprobar(fecha, inicio, fin));
        assertEquals(409, error.getStatusCode().value());
        return error;
    }

    private Rol rol(String nombre) {
        Rol rol = new Rol();
        rol.setNombreRol(nombre);
        return rol;
    }
}
