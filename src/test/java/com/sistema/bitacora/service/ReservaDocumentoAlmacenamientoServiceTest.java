package com.sistema.bitacora.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReservaDocumentoAlmacenamientoServiceTest {

    @TempDir
    Path temporal;

    private Path directorio;
    private ReservaDocumentoAlmacenamientoService servicio;

    @BeforeEach
    void preparar() {
        // Las pruebas no utilizan la carpeta real de documentos.
        directorio = temporal.resolve("documentos");

        servicio = new ReservaDocumentoAlmacenamientoService(
                directorio.toString(),
                new ReservaPdfValidacionService()
        );
    }

    @Test
    void guardaExactamenteElContenidoValidado() throws IOException {
        byte[] contenido = crearPdf();

        var resultado = servicio.guardar(
                archivo("oficio.pdf", contenido)
        );

        Path guardado = directorio.resolve(resultado.referencia());

        assertTrue(Files.isRegularFile(guardado));
        assertArrayEquals(contenido, Files.readAllBytes(guardado));
        assertEquals(contenido.length, resultado.tamanioBytes());
        assertEquals(1, resultado.paginas());
        assertTrue(resultado.referencia().endsWith(".pdf"));

        String identificador = resultado.referencia().substring(
                0,
                resultado.referencia().length() - 4
        );

        assertDoesNotThrow(() -> UUID.fromString(identificador));
    }

    @Test
    void generaReferenciasDistintasSinSobrescribir() throws IOException {
        byte[] contenido = crearPdf();

        var primero = servicio.guardar(
                archivo("oficio.pdf", contenido)
        );

        var segundo = servicio.guardar(
                archivo("oficio.pdf", contenido)
        );

        assertNotEquals(
                primero.referencia(),
                segundo.referencia()
        );

        assertArrayEquals(
                contenido,
                Files.readAllBytes(
                        directorio.resolve(primero.referencia())
                )
        );

        assertArrayEquals(
                contenido,
                Files.readAllBytes(
                        directorio.resolve(segundo.referencia())
                )
        );

        try (var archivos = Files.list(directorio)) {
            assertEquals(2L, archivos.count());
        }
    }

    @Test
    void rechazaContenidoInvalidoSinCrearLaCarpeta() {
        byte[] contenido = "Esto no es un PDF"
                .getBytes(StandardCharsets.UTF_8);

        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.guardar(
                        archivo("oficio.pdf", contenido)
                )
        );

        assertEquals(400, error.getStatusCode().value());
        assertFalse(Files.exists(directorio));
    }

    @Test
    void noUsaLaRutaDelNombreOriginal() throws IOException {
        byte[] contenido = crearPdf();

        var resultado = servicio.guardar(
                archivo("../fuera.pdf", contenido)
        );

        Path guardado = directorio
                .resolve(resultado.referencia())
                .normalize();

        assertEquals(directorio, guardado.getParent());
        assertTrue(Files.isRegularFile(guardado));
        assertFalse(Files.exists(temporal.resolve("fuera.pdf")));
        assertFalse(resultado.referencia().contains(".."));
        assertFalse(resultado.referencia().contains("/"));
        assertFalse(resultado.referencia().contains("\\"));
    }

    private MockMultipartFile archivo(
            String nombre,
            byte[] contenido) {

        return new MockMultipartFile(
                "oficioReservacion",
                nombre,
                "application/pdf",
                contenido
        );
    }

    private byte[] crearPdf() throws IOException {
        try (PDDocument documento = new PDDocument();
             ByteArrayOutputStream salida =
                     new ByteArrayOutputStream()) {

            documento.addPage(new PDPage());
            documento.save(salida);

            return salida.toByteArray();
        }
    }
}
