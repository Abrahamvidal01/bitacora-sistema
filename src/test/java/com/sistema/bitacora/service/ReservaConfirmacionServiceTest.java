package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Departamento;
import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.InventarioEspacio;
import com.sistema.bitacora.entity.NombramientoDepartamento;
import com.sistema.bitacora.entity.Rol;
import com.sistema.bitacora.entity.SolicitudEspacio;
import com.sistema.bitacora.entity.SolicitudRecurso;
import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Pruebas unitarias de orquestación. No inician Spring ni MariaDB.
// Los mocks NO prueban commit/rollback, aislamiento, bloqueos reales ni limpieza en disco.
// La autenticación aquí comprueba la validación imperativa, no el filtro HTTP ni @PreAuthorize.
class ReservaConfirmacionServiceTest {
    private static final String CLAVE = "f9bb3c30-e277-4c1a-bdaa-b73f47c3c8e9";
    private static final String ADMIN = "admin@ejemplo.test";
    private static final String CORREO = "titular@ejemplo.test";
    private static final LocalDate FECHA = LocalDate.of(2026, 11, 12);
    private static final LocalTime INICIO = LocalTime.of(9, 0);
    private static final LocalTime FIN = LocalTime.of(11, 0);
    private static final String FOLIO = "RES-2026-000001";

    private EntityManager em;
    private UsuarioRepository usuarios;
    private ReservaValidacionService validacion;
    private ReservaBloqueoService bloqueos;
    private ReservaCupoValidacionService cupos;
    private ReservaPdfValidacionService pdf;
    private ReservaDocumentoAlmacenamientoService documentos;
    private FolioReservaService folios;
    private ReservaConfirmacionService servicio;
    private Query consultaRepeticion;
    private Query insercionConsumo;
    private Query insercionConfirmacion;
    private Usuario actor;
    private Usuario titular;
    private Departamento departamento;
    private Espacio espacio;
    private InventarioEspacio articulo;
    private List<Object> persistidos;

    @BeforeEach
    void preparar() {
        em = mock(EntityManager.class);
        usuarios = mock(UsuarioRepository.class);
        validacion = mock(ReservaValidacionService.class);
        bloqueos = mock(ReservaBloqueoService.class);
        cupos = mock(ReservaCupoValidacionService.class);
        pdf = mock(ReservaPdfValidacionService.class);
        documentos = mock(ReservaDocumentoAlmacenamientoService.class);
        folios = mock(FolioReservaService.class);
        consultaRepeticion = consulta();
        insercionConsumo = consulta();
        insercionConfirmacion = consulta();
        when(consultaRepeticion.getResultList()).thenReturn(List.of());
        when(insercionConsumo.executeUpdate()).thenReturn(1);
        when(insercionConfirmacion.executeUpdate()).thenReturn(1);
        when(em.createNativeQuery(anyString())).thenAnswer(invocacion -> {
            String sql = invocacion.getArgument(0);
            if (sql.contains("SELECT c.registrado_por_usuario_id")) return consultaRepeticion;
            if (sql.contains("INSERT INTO consumo_cupos_reserva")) return insercionConsumo;
            if (sql.contains("INSERT INTO confirmaciones_reserva")) return insercionConfirmacion;
            throw new AssertionError("SQL inesperado: " + sql);
        });
        departamento = new Departamento();
        departamento.setId(1L);
        departamento.setActivo(true);
        departamento.setCupoMensual(1);
        espacio = new Espacio();
        espacio.setId(2L);
        espacio.setActivo(true);
        espacio.setEstadoActual("DISPONIBLE");
        espacio.setMinutosLimpieza(30);
        actor = usuario(10L, ADMIN, "ADMINISTRADOR");
        titular = usuario(20L, CORREO, "SOLICITANTE");
        when(usuarios.findByCorreoInstitucional(ADMIN)).thenReturn(Optional.of(actor));
        when(usuarios.findByCorreoInstitucional(CORREO)).thenReturn(Optional.of(titular));
        when(bloqueos.bloquearParaConfirmar(1L, 2L))
                .thenReturn(new ReservaBloqueoService.BloqueoReserva(departamento, espacio));
        var nombramiento = new NombramientoDepartamento();
        nombramiento.setId(3L);
        nombramiento.setDepartamento(departamento);
        nombramiento.setUsuario(titular);
        nombramiento.setFechaInicio(FECHA.minusMonths(2).atStartOfDay());
        when(em.find(NombramientoDepartamento.class, 3L)).thenReturn(nombramiento);
        var resultado = new ReservaValidacionService.ResultadoValidacionInicial(
                10L, 20L, 1L, 3L, FECHA.atTime(INICIO), FECHA.atTime(FIN), "America/Mexico_City");
        when(validacion.validarParaAdministrador(2L, CORREO, FECHA, INICIO, FIN)).thenReturn(resultado);
        when(validacion.validarUrgenciaParaAdministrador(
                eq(2L), eq(CORREO), eq(FECHA), eq(INICIO), eq(FIN), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocacion -> new ReservaValidacionService.ResultadoUrgenciaAdministrativa(
                        resultado, invocacion.getArgument(5),
                        ((Boolean) invocacion.getArgument(7))
                                || !((String) invocacion.getArgument(6)).isBlank()
                                ? ReservaValidacionService.ModalidadUrgencia.CON_REQUISICION
                                : ReservaValidacionService.ModalidadUrgencia.SOLO_INVENTARIO));
        articulo = new InventarioEspacio();
        articulo.setId(7L);
        articulo.setEspacio(espacio);
        articulo.setNombreItem("Sillas");
        articulo.setDescripcion("Sillas de plástico");
        articulo.setEstadoBien("BUENO");
        articulo.setCantidad(30);
        when(em.find(InventarioEspacio.class, 7L)).thenReturn(articulo);
        when(documentos.guardarEnTransaccion(any(MultipartFile.class))).thenAnswer(invocacion -> {
            MultipartFile archivo = invocacion.getArgument(0);
            return new ReservaDocumentoAlmacenamientoService.DocumentoGuardado(
                    archivo.getName().equals("requisicion") ? "requisicion-privada.pdf" : "oficio-privado.pdf",
                    3, 1);
        });
        when(folios.generarParaReserva()).thenReturn(FOLIO);
        persistidos = new ArrayList<>();
        doAnswer(invocacion -> {
            Object entidad = invocacion.getArgument(0);
            if (entidad instanceof SolicitudEspacio solicitud) solicitud.setId(101L);
            persistidos.add(entidad);
            return null;
        }).when(em).persist(any());
        servicio = servicio(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ADMIN, "no-utilizado",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))));
    }

    @AfterEach
    void limpiarAutenticacion() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void confirmacionDeshabilitadaNoConsultaNiGuardaDatos() {
        var deshabilitado = servicio(false);
        assertFalse(deshabilitado.habilitada());
        var error = assertThrows(ResponseStatusException.class,
                () -> deshabilitado.confirmar(entrada()));
        assertEquals(503, error.getStatusCode().value());
        verifyNoInteractions(usuarios, bloqueos, em, documentos, folios);
    }

    @Test
    void cuentaSinRolAdministradorNoPuedeConfirmar() {
        actor.setRoles(new HashSet<>());
        var error = assertThrows(ResponseStatusException.class, () -> servicio.confirmar(entrada()));
        assertEquals(403, error.getStatusCode().value());
        verifyNoInteractions(bloqueos, em, documentos, folios);
    }

    @Test
    void registraReservaInternaAprobadaConDetalleHistoricoYCupo() {
        var resultado = servicio.confirmar(entrada());
        assertEquals(101L, resultado.solicitudId());
        assertEquals(FOLIO, resultado.folio());
        assertEquals("APROBADA", resultado.estatus());
        assertFalse(resultado.repetida());
        SolicitudEspacio solicitud = solicitudPersistida();
        assertSame(actor, solicitud.getRegistradoPor());
        assertSame(titular, solicitud.getUsuario());
        assertSame(departamento, solicitud.getDepartamentoSolicitante());
        assertEquals("oficio-privado.pdf", solicitud.getRutaOficioReservacion());
        assertNull(solicitud.getRutaRequisicion());
        SolicitudRecurso detalle = persistidos.stream().filter(SolicitudRecurso.class::isInstance)
                .map(SolicitudRecurso.class::cast).findFirst().orElseThrow();
        assertEquals(101L, detalle.getSolicitudId());
        assertEquals(7L, detalle.getInventarioId());
        assertEquals(2L, detalle.getEspacioId());
        assertEquals("Sillas", detalle.getNombreItem());
        assertEquals("Sillas de plástico", detalle.getDescripcionItem());
        assertEquals("BUENO", detalle.getEstadoBien());
        assertEquals(5, detalle.getCantidadSolicitada());
        assertEquals(30, detalle.getExistenciasAlConfirmar());
        assertEquals(30, articulo.getCantidad(), "No debe descontar permanentemente el inventario");
        verify(cupos).validar(1L, FECHA);
        verify(insercionConsumo).executeUpdate();
        verify(insercionConfirmacion).setParameter("modalidad", "NORMAL");
        verify(insercionConfirmacion).setParameter("motivoUrgencia", null);
    }

    @Test
    void recursosExternosConRequisicionQuedanPendientesDelSupervisor() {
        var resultado = servicio.confirmar(entrada("Sonido externo", requisicion(), false, "", recursos()));
        assertEquals("PENDIENTE", resultado.estatus());
        assertEquals("requisicion-privada.pdf", solicitudPersistida().getRutaRequisicion());
        verify(documentos, times(2)).guardarEnTransaccion(any(MultipartFile.class));
    }

    @Test
    void noPermiteOmitirElOficio() {
        var original = entrada();
        var sinOficio = new ReservaConfirmacionService.EntradaConfirmacion(
                original.claveConfirmacion(), original.espacioId(), original.correoSolicitante(),
                original.fechaEvento(), original.horaInicio(), original.horaFin(), original.motivoEvento(),
                "", false, "", original.recursos(), null, null);
        var error = assertThrows(ResponseStatusException.class, () -> servicio.confirmar(sinOficio));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(bloqueos, documentos, folios);
    }

    @Test
    void recursosDuplicadosSeRechazanAntesDeBloquear() {
        var duplicados = List.of(new ReservaRecursosValidacionService.SolicitudRecurso(7L, 2),
                new ReservaRecursosValidacionService.SolicitudRecurso(7L, 3));
        var error = assertThrows(ResponseStatusException.class,
                () -> servicio.confirmar(entrada("", null, false, "", duplicados)));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(bloqueos, documentos, folios);
    }

    @Test
    void articuloMaloSeRechazaSinGuardarDocumentosNiConsumirCupo() {
        articulo.setEstadoBien("MALO");
        var error = assertThrows(ResponseStatusException.class, () -> servicio.confirmar(entrada()));
        assertEquals(409, error.getStatusCode().value());
        verifyNoInteractions(cupos, documentos, folios);
        verify(em, never()).persist(any());
    }

    @Test
    void cupoAgotadoImpideEscribirDocumentosYReservas() {
        when(cupos.validar(1L, FECHA)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT, "El departamento agotó su cupo"));
        var error = assertThrows(ResponseStatusException.class, () -> servicio.confirmar(entrada()));
        assertEquals(409, error.getStatusCode().value());
        verifyNoInteractions(documentos, folios);
        verify(em, never()).persist(any());
    }

    @Test
    void repetirMismaClaveYMismosDatosDevuelveLaReservaSinNuevasEscrituras() {
        servicio.confirmar(entrada());
        String firma = firmaRegistrada();
        when(consultaRepeticion.getResultList()).thenReturn(
                List.of((Object) new Object[]{10L, firma, 101L, FOLIO, "APROBADA"}));
        clearInvocations(em, documentos, folios, cupos, insercionConsumo, insercionConfirmacion);

        var resultado = servicio.confirmar(entrada());

        assertTrue(resultado.repetida());
        assertEquals(101L, resultado.solicitudId());
        assertEquals(FOLIO, resultado.folio());
        verifyNoInteractions(documentos, folios, cupos);
        verify(em, never()).persist(any());
        verify(insercionConsumo, never()).executeUpdate();
        verify(insercionConfirmacion, never()).executeUpdate();
    }

    @Test
    void mismaClaveConOtroContenidoSeRechazaSinDuplicarLaReserva() {
        servicio.confirmar(entrada());
        String firma = firmaRegistrada();
        when(consultaRepeticion.getResultList()).thenReturn(
                List.of((Object) new Object[]{10L, firma, 101L, FOLIO, "APROBADA"}));
        clearInvocations(em, documentos, folios, cupos);
        var cambiados = List.of(new ReservaRecursosValidacionService.SolicitudRecurso(7L, 6));

        var error = assertThrows(ResponseStatusException.class,
                () -> servicio.confirmar(entrada("", null, false, "", cambiados)));

        assertEquals(409, error.getStatusCode().value());
        verifyNoInteractions(documentos, folios, cupos);
        verify(em, never()).persist(any());
    }

    @Test
    void registraMotivoYModalidadDeUrgenciaSinConfundirlaConAusenciaDeOficio() {
        servicio.confirmar(entrada("", null, true, "No pudo ingresar", recursos()));
        verify(insercionConfirmacion).setParameter("modalidad", "URGENTE_INVENTARIO");
        verify(insercionConfirmacion).setParameter("motivoUrgencia", "No pudo ingresar");
        assertEquals(Boolean.FALSE, solicitudPersistida().getEsUrgenteSinOficio());
        verify(validacion, times(2)).validarUrgenciaParaAdministrador(
                2L, CORREO, FECHA, INICIO, FIN, "No pudo ingresar", "", false);
    }

    @Test
    void existenciasInsuficientesSeRechazanAntesDeGuardar() {
        articulo.setCantidad(4);
        var error = assertThrows(ResponseStatusException.class, () -> servicio.confirmar(entrada()));
        assertEquals(409, error.getStatusCode().value());
        verifyNoInteractions(cupos, documentos, folios);
        verify(em, never()).persist(any());
    }

    @Test
    void propagaElFalloDelSegundoDocumentoParaQueLaTransaccionPuedaRevertirse() {
        when(documentos.guardarEnTransaccion(any(MultipartFile.class)))
                .thenReturn(new ReservaDocumentoAlmacenamientoService.DocumentoGuardado("oficio.pdf", 3, 1))
                .thenThrow(new IllegalStateException("Disco no disponible"));
        assertThrows(IllegalStateException.class,
                () -> servicio.confirmar(entrada("Sonido externo", requisicion(), false, "", recursos())));
        verify(documentos, times(2)).guardarEnTransaccion(any(MultipartFile.class));
        verifyNoInteractions(folios);
        verify(em, never()).persist(any());
        // La limpieza real del primer PDF requiere una prueba de integración, no este mock.
    }

    @Test
    void siFallaElRegistroDelConsumoNoDevuelveUnExito() {
        when(insercionConsumo.executeUpdate()).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> servicio.confirmar(entrada()));
        verify(insercionConfirmacion, never()).executeUpdate();
        // Aquí solo comprobamos propagación. No se afirma que Mockito ejecute un rollback.
    }

    private ReservaConfirmacionService servicio(boolean habilitada) {
        return new ReservaConfirmacionService(em, usuarios, validacion, bloqueos, cupos,
                new ReservaFormularioValidacionService(), pdf, documentos, folios, habilitada);
    }

    private Query consulta() {
        Query consulta = mock(Query.class);
        when(consulta.setParameter(anyString(), any())).thenReturn(consulta);
        return consulta;
    }

    private Usuario usuario(Long id, String correo, String nombreRol) {
        Rol rol = mock(Rol.class);
        when(rol.getNombreRol()).thenReturn(nombreRol);
        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setCorreoInstitucional(correo);
        usuario.setActivo(true);
        usuario.setDepartamento(departamento);
        usuario.setRoles(new HashSet<>(List.of(rol)));
        return usuario;
    }

    private List<ReservaRecursosValidacionService.SolicitudRecurso> recursos() {
        return List.of(new ReservaRecursosValidacionService.SolicitudRecurso(7L, 5));
    }

    private MockMultipartFile oficio() {
        return new MockMultipartFile("oficioReservacion", "oficio.pdf", "application/pdf", new byte[]{1, 2, 3});
    }

    private MockMultipartFile requisicion() {
        return new MockMultipartFile("requisicion", "requisicion.pdf", "application/pdf", new byte[]{4, 5, 6});
    }

    private ReservaConfirmacionService.EntradaConfirmacion entrada() {
        return entrada("", null, false, "", recursos());
    }

    private ReservaConfirmacionService.EntradaConfirmacion entrada(String externos, MultipartFile requisicion,
                                                                   boolean urgente, String motivoUrgencia, List<ReservaRecursosValidacionService.SolicitudRecurso> seleccion) {
        return new ReservaConfirmacionService.EntradaConfirmacion(CLAVE, 2L, CORREO, FECHA, INICIO, FIN,
                "Reunión académica", externos, urgente, motivoUrgencia, seleccion, oficio(), requisicion);
    }

    private SolicitudEspacio solicitudPersistida() {
        return persistidos.stream().filter(SolicitudEspacio.class::isInstance)
                .map(SolicitudEspacio.class::cast).findFirst().orElseThrow();
    }

    private String firmaRegistrada() {
        ArgumentCaptor<Object> firma = ArgumentCaptor.forClass(Object.class);
        verify(insercionConfirmacion).setParameter(eq("firma"), firma.capture());
        return (String) firma.getValue();
    }
}