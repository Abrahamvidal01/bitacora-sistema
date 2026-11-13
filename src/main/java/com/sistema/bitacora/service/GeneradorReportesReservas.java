package com.sistema.bitacora.service;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class GeneradorReportesReservas {
    private static final String[] TITULOS = {"Folio", "Solicitante", "Departamento", "Espacio",
            "Fecha del evento", "Inicio", "Fin", "Registro", "Estado", "Modalidad"};
    public byte[] pdf(HistorialAdminExportacionService.Datos datos) throws IOException {
        try (PDDocument documento = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDFont fuente = fuente(documento);
            try (Paginas paginas = new Paginas(documento, fuente, datos.mes())) {
                paginas.linea("Historial de urgencias administrativas", 14);
                paginas.linea("Mes del evento: " + datos.mes() + " | Reservas: " + datos.filas().size(), 10);
                paginas.linea("Incluye urgencias de inventario y requisicion, en todos sus estados.", 9);
                if (datos.filas().isEmpty()) paginas.linea("Sin reservas para el mes seleccionado.", 10);
                int numero = 0;
                for (var fila : datos.filas()) {
                    paginas.linea("-----------------------------------------------------------", 9);
                    paginas.linea("Reserva " + (++numero), 10);
                    var valores = valores(fila);
                    for (int i = 0; i < TITULOS.length; i++) paginas.linea(TITULOS[i] + ": " + valores.get(i), 9);
                }
            }
            documento.getDocumentInformation().setTitle("Reservas urgentes " + datos.mes());
            documento.save(bytes); return bytes.toByteArray();
        }
    }
    // XLSX OOXML: texto inline, sin macros, formulas ni enlaces externos. No es CSV renombrado.
    public byte[] excel(HistorialAdminExportacionService.Datos datos) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                entrada(zip, "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                        <Default Extension="xml" ContentType="application/xml"/>
                        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                        <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                        </Types>
                        """);
                entrada(zip, "_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                        </Relationships>
                        """);
                entrada(zip, "xl/workbook.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView/></bookViews><sheets><sheet name="Reservas" sheetId="1" r:id="rId1"/></sheets></workbook>
                        """);
                entrada(zip, "xl/_rels/workbook.xml.rels", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                        </Relationships>
                        """);
                StringBuilder hoja = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                        + "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"
                        + "<cols><col min=\"1\" max=\"10\" width=\"26\" customWidth=\"1\"/></cols><sheetData>");
                filaExcel(hoja, 1, List.of(TITULOS)); int numero = 1;
                for (var fila : datos.filas()) filaExcel(hoja, ++numero, valores(fila));
                hoja.append("</sheetData><autoFilter ref=\"A1:J").append(numero).append("\"/></worksheet>");
                entrada(zip, "xl/worksheets/sheet1.xml", hoja.toString());
            }
            return bytes.toByteArray();
        }
    }
    private static List<String> valores(DashboardAdminService.ReservaHistorial r) {
        return List.of(texto(r.folio()), texto(r.solicitante()), texto(r.departamento()), texto(r.espacio()),
                texto(r.fecha()), texto(r.inicio()), texto(r.fin()), texto(r.registro()), texto(r.estado()), texto(r.modalidad()));
    }
    private static String texto(String valor) {
        if (valor == null) return "";
        StringBuilder limpio = new StringBuilder();
        valor.codePoints().forEach(c -> {
            if (c == 9 || c == 10 || c == 13 || (c >= 32 && c <= 0xD7FF)
                    || (c >= 0xE000 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0x10FFFF)) limpio.appendCodePoint(c);
            else limpio.append(' ');
        });
        if (limpio.length() > 32767) throw new IllegalArgumentException("Un campo supera el limite de Excel");
        return limpio.toString();
    }
    private static String xml(String valor) {
        return valor.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
    private static void filaExcel(StringBuilder hoja, int numero, List<String> valores) {
        hoja.append("<row r=\"").append(numero).append("\">");
        for (int i = 0; i < valores.size(); i++) hoja.append("<c r=\"").append((char) ('A' + i)).append(numero)
                .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(valores.get(i))).append("</t></is></c>");
        hoja.append("</row>");
    }
    private static void entrada(ZipOutputStream zip, String nombre, String contenido) throws IOException {
        zip.putNextEntry(new ZipEntry(nombre)); zip.write(contenido.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    private static PDFont fuente(PDDocument documento) throws IOException {
        String windows = System.getenv("WINDIR");
        List<Path> candidatas = List.of(Path.of(windows == null ? "C:\\Windows" : windows, "Fonts", "arial.ttf"),
                Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
                Path.of("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"));
        for (Path ruta : candidatas) if (Files.isRegularFile(ruta)) {
            try (var entrada = Files.newInputStream(ruta)) { return PDType0Font.load(documento, entrada); }
        }
        // Si un caracter no es representable, falla sin entregar PDF parcial.
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }
    private static final class Paginas implements Closeable {
        private final PDDocument documento;
        private final PDFont fuente;
        private final String mes;
        private PDPageContentStream salida;
        private float y;
        private int numero;
        private Paginas(PDDocument documento, PDFont fuente, String mes) { this.documento = documento; this.fuente = fuente; this.mes = mes; }
        private void nueva() throws IOException {
            cerrarPagina(); PDPage pagina = new PDPage(PDRectangle.A4); documento.addPage(pagina);
            salida = new PDPageContentStream(documento, pagina); numero++; y = 795;
        }
        private void dibujar(String texto, float tam, float x, float y) throws IOException {
            salida.beginText(); salida.setFont(fuente, tam); salida.newLineAtOffset(x, y); salida.showText(texto); salida.endText();
        }
        private void linea(String valor, float tam) throws IOException {
            String t = texto(valor).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
            StringBuilder linea = new StringBuilder();
            for (int i = 0; i < t.length();) {
                int c = t.codePointAt(i); String letra = new String(Character.toChars(c)); String siguiente = linea + letra;
                if (fuente.getStringWidth(siguiente) * tam / 1000 > 510 && !linea.isEmpty()) {
                    imprimir(linea.toString(), tam); linea.setLength(0);
                }
                linea.append(letra); i += Character.charCount(c);
            }
            imprimir(linea.toString(), tam);
        }
        private void imprimir(String texto, float tam) throws IOException {
            if (salida == null || y < 55) nueva(); dibujar(texto, tam, 40, y); y -= tam + 4;
        }
        private void cerrarPagina() throws IOException {
            if (salida != null) {
                try { dibujar("Mes " + mes + " | Pagina " + numero, 8, 40, 27); }
                finally { salida.close(); salida = null; }
            }
        }
        @Override public void close() throws IOException { cerrarPagina(); }
    }
}
