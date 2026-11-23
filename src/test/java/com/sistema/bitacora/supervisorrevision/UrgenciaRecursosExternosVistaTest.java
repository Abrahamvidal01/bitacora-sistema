package com.sistema.bitacora.supervisorrevision;

import com.sistema.bitacora.entity.SolicitudEspacio;
import com.sistema.bitacora.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unitario con mocks: mapeo del DTO de lectura, sin Spring Boot, MariaDB ni archivos PDF. */
class UrgenciaRecursosExternosVistaTest {
    private EntityManager em;
    private SolicitudEspacio solicitud;
    private UrgenciaRevisionService service;
    private UrgenciaDocumentos documents;
    private UrgenciaCupo cupo;
    private UrgenciaAuditWriter audit;

    @BeforeEach
    void preparar() {
        em = mock(EntityManager.class);
        solicitud = mock(SolicitudEspacio.class, RETURNS_DEEP_STUBS);
        documents = mock(UrgenciaDocumentos.class);
        cupo = mock(UrgenciaCupo.class);
        audit = mock(UrgenciaAuditWriter.class);
        service = new UrgenciaRevisionService(em, mock(UsuarioRepository.class), documents, cupo, audit, Clock.systemUTC());
        Query modalidad = query(List.of("URGENTE_REQUISICION"));
        Query ultima = query(List.of());
        Query recursos = query(Collections.singletonList(new Object[]{31L, 2, "Silla de prueba", "BUENO"}));
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT modalidad FROM confirmaciones_reserva")) return modalidad;
            if (sql.startsWith("SELECT revision_despues,documentos_despues FROM revision_urgencias_eventos")) return ultima;
            if (sql.startsWith("SELECT inventario_id,cantidad_solicitada,nombre_item,estado_bien FROM solicitud_recursos")) return recursos;
            throw new AssertionError("Consulta no esperada en el mapeo de solo lectura");
        });
        when(solicitud.getId()).thenReturn(101L);
        when(solicitud.getFolio()).thenReturn("UI-PRUEBA-SIN-BASE");
        when(solicitud.getUsuario().getId()).thenReturn(7L);
        when(solicitud.getUsuario().getNombreCompleto()).thenReturn("Identidad sintetica");
        when(solicitud.getDepartamentoSolicitante().getId()).thenReturn(8L);
        when(solicitud.getDepartamentoSolicitante().getNombreDepartamento()).thenReturn("Departamento de prueba");
        when(solicitud.getNombramiento().getId()).thenReturn(9L);
        when(solicitud.getRegistradoPor().getId()).thenReturn(7L);
        when(solicitud.getEspacio().getId()).thenReturn(10L);
        when(solicitud.getEspacio().getNombreEspacio()).thenReturn("Espacio de prueba");
        when(solicitud.getFechaEvento()).thenReturn(LocalDate.of(2030, 2, 3));
        when(solicitud.getHoraInicio()).thenReturn(LocalTime.of(10, 0));
        when(solicitud.getHoraFin()).thenReturn(LocalTime.of(12, 0));
        when(solicitud.getMotivoEvento()).thenReturn("Motivo del evento, distinto al motivo de decision");
        when(solicitud.getEstatusSolicitud()).thenReturn("PENDIENTE");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Query query(List<?> rows) {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.setMaxResults(anyInt())).thenReturn(q);
        when(q.getResultList()).thenReturn((List) rows);
        return q;
    }

    private UrgenciaRevisionService.Row row() {
        UrgenciaRevisionService.Row result = ReflectionTestUtils.invokeMethod(service, "row", solicitud);
        assertNotNull(result);
        return result;
    }

    @Test
    void conservaTextoExternoYSeparaInventarioInstitucional() {
        String text = "Proveedor externo: nombre de prueba\nEquipo declarado: 2 pantallas\n<img src=x onerror=alert(1)>";
        when(solicitud.getRecursosExternos()).thenReturn(text);
        var result = row();
        assertEquals(text, result.recursosExternos());
        assertEquals("Motivo del evento, distinto al motivo de decision", result.motivo());
        assertEquals(1, result.recursos().size());
        assertEquals(31L, result.recursos().getFirst().inventarioId());
        assertEquals(2, result.recursos().getFirst().cantidad());
        assertEquals("Silla de prueba", result.recursos().getFirst().nombre());
        verifyNoInteractions(documents, cupo, audit);
        verify(em, never()).flush();
        verify(em, never()).persist(any());
        // La ausencia de interpretacion HTML se debe comprobar tambien en navegador: este test es Java.
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void noInventaDatosCuandoElTextoEsNuloOVacio(String text) {
        when(solicitud.getRecursosExternos()).thenReturn(text);
        assertEquals(text, row().recursosExternos());
    }

    @Test
    void cambiarTextoExternoYaCambiaLaVersionDelSnapshotExistente() {
        when(solicitud.getRecursosExternos()).thenReturn("Texto A");
        String first = row().version();
        when(solicitud.getRecursosExternos()).thenReturn("Texto B");
        assertNotEquals(first, row().version());
        // No se cambia la formula productiva de version: immutable() ya incluia este dato.
    }

    @Test
    void contratoColocaElTextoTrasMotivoSinExponerRutasPrivadas() {
        var components = UrgenciaRevisionService.Row.class.getRecordComponents();
        List<String> names = Arrays.stream(components).map(c -> c.getName()).toList();
        int index = names.indexOf("recursosExternos");
        assertTrue(index > 0);
        assertEquals("motivo", names.get(index - 1));
        assertEquals("modalidad", names.get(index + 1));
        assertEquals(String.class, components[index].getType());
        assertEquals(1, names.stream().filter("recursosExternos"::equals).count());
        assertFalse(names.stream().anyMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("ruta")));
    }
}
