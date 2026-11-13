package com.sistema.bitacora.service;
import com.sistema.bitacora.controller.ReporteReservasAdminController;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Unitarias/formato: no equivalen a pruebas reales MariaDB, HTTP ni permisos.
class HistorialAdminExportacionTest {
    private DashboardAdminService dashboard;
    private HistorialAdminExportacionService servicio;
    @BeforeEach void preparar() { dashboard = mock(DashboardAdminService.class); servicio = new HistorialAdminExportacionService(dashboard); }
    private DashboardAdminService.ReservaHistorial fila(String nombre) {
        return new DashboardAdminService.ReservaHistorial("RES-2026-000001", nombre, "Departamento", "Sala",
                "2026-11-16", "10:00", "12:00", "2026-11-13 12:00", "PENDIENTE", "URGENTE_REQUISICION");
    }
    @Test void incluyeTodasLasPaginas() {
        List<DashboardAdminService.ReservaHistorial> primera = new ArrayList<>();
        for (int i = 0; i < 20; i++) primera.add(fila("Persona " + i));
        when(dashboard.historial("2026-11", 0)).thenReturn(new DashboardAdminService.Historial("2026-11", 0, 21, 2, primera));
        when(dashboard.historial("2026-11", 1)).thenReturn(new DashboardAdminService.Historial("2026-11", 1, 21, 2, List.of(fila("Ultima"))));
        assertEquals(21, servicio.consultar("2026-11").filas().size()); verify(dashboard).historial("2026-11", 1);
    }
    @Test void historialVacioEsValido() {
        when(dashboard.historial(null, 0)).thenReturn(new DashboardAdminService.Historial("2026-11", 0, 0, 1, List.of()));
        var datos = servicio.consultar(null); assertEquals("2026-11", datos.mes()); assertTrue(datos.filas().isEmpty());
    }
    @Test void rechazaMesExcedidoSinRecortar() {
        when(dashboard.historial("2026-11", 0)).thenReturn(new DashboardAdminService.Historial("2026-11", 0, 2001, 101, List.of()));
        assertThrows(ResponseStatusException.class, () -> servicio.consultar("2026-11")); verify(dashboard, never()).historial(anyString(), eq(1));
    }
    @Test void rechazaResultadoIncompleto() {
        when(dashboard.historial("2026-11", 0)).thenReturn(new DashboardAdminService.Historial("2026-11", 0, 1, 1, List.of()));
        assertThrows(IllegalStateException.class, () -> servicio.consultar("2026-11"));
    }
    @Test void rechazaCambioDeTotalEntrePaginas() {
        when(dashboard.historial("2026-11", 0)).thenReturn(new DashboardAdminService.Historial("2026-11", 0, 21, 2, List.of(fila("A"))));
        when(dashboard.historial("2026-11", 1)).thenReturn(new DashboardAdminService.Historial("2026-11", 1, 22, 2, List.of(fila("B"))));
        assertThrows(IllegalStateException.class, () -> servicio.consultar("2026-11"));
    }
    @Test void excelEsZipXmlConVistasValidasYSinFormulas() throws Exception {
        byte[] bytes = new GeneradorReportesReservas().excel(new HistorialAdminExportacionService.Datos("2026-11", List.of(fila("=1+1 & <prueba>"))));
        var entradas = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entrada; while ((entrada = zip.getNextEntry()) != null) entradas.put(entrada.getName(), zip.readAllBytes());
        }
        assertEquals(5, entradas.size()); assertTrue(entradas.containsKey("[Content_Types].xml"));
        DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance(); fabrica.setNamespaceAware(true);
        fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (byte[] xml : entradas.values()) fabrica.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        var hoja = fabrica.newDocumentBuilder().parse(new ByteArrayInputStream(entradas.get("xl/worksheets/sheet1.xml")));
        var libro = fabrica.newDocumentBuilder().parse(new ByteArrayInputStream(entradas.get("xl/workbook.xml")));
        int vistas = libro.getElementsByTagNameNS("*", "workbookView").getLength(); assertTrue(vistas > 0);
        var vistasHoja = hoja.getElementsByTagNameNS("*", "sheetView");
        for (int i = 0; i < vistasHoja.getLength(); i++) {
            int referencia = Integer.parseInt(((org.w3c.dom.Element) vistasHoja.item(i)).getAttribute("workbookViewId"));
            assertTrue(referencia >= 0 && referencia < vistas);
        }
        assertEquals(0, hoja.getElementsByTagNameNS("*", "f").getLength());
        assertTrue(hoja.getDocumentElement().getTextContent().contains("=1+1 & <prueba>"));
        assertEquals(2, hoja.getElementsByTagNameNS("*", "row").getLength());
    }
    @Test void pdfPuedeAbrirseYContieneTextoEspanol() throws Exception {
        byte[] bytes = new GeneradorReportesReservas().pdf(new HistorialAdminExportacionService.Datos("2026-11", List.of(fila("Maria Muñoz"))));
        try (var documento = Loader.loadPDF(bytes)) {
            assertTrue(documento.getNumberOfPages() > 0); String texto = new org.apache.pdfbox.text.PDFTextStripper().getText(documento);
            assertTrue(texto.contains("Muñoz")); assertTrue(texto.contains("RES-2026-000001"));
        }
    }
    @Test void formatoInvalidoNoConsultaDatos() {
        var lector = mock(HistorialAdminExportacionService.class);
        var controlador = new ReporteReservasAdminController(lector, new GeneradorReportesReservas());
        assertThrows(ResponseStatusException.class, () -> controlador.descargar("2026-11", "csv")); verifyNoInteractions(lector);
    }
    @Test void descargaExcelEsPrivadaYConNombreDelMes() {
        var lector = mock(HistorialAdminExportacionService.class);
        when(lector.consultar("2026-11")).thenReturn(new HistorialAdminExportacionService.Datos("2026-11", List.of()));
        var respuesta = new ReporteReservasAdminController(lector, new GeneradorReportesReservas()).descargar("2026-11", "xlsx");
        assertTrue(respuesta.getHeaders().getCacheControl().contains("no-store"));
        assertTrue(respuesta.getHeaders().getFirst("Content-Disposition").contains("2026-11.xlsx"));
        assertNotNull(respuesta.getBody()); assertTrue(respuesta.getBody().length > 0);
    }
}
