package com.sistema.bitacora.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ReservaPdfValidacionServiceTest {

    private final ReservaPdfValidacionService servicio =
            new ReservaPdfValidacionService();

    @Test
    void aceptaPdfValidoDeUnaPagina() throws IOException {
        byte[] contenido = crearPdf();

        var resultado = servicio.validar(
                archivo("oficio.pdf", contenido)
        );

        assertEquals(contenido.length, resultado.tamanioBytes());
        assertEquals(1, resultado.paginas());
    }

    @Test
    void aceptaExtensionEnMayusculas() throws IOException {
        var resultado = servicio.validar(
                archivo("OFICIO.PDF", crearPdf())
        );

        assertEquals(1, resultado.paginas());
    }

    @Test
    void rechazaArchivoNulo() {
        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validar(null)
        );

        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void rechazaArchivoVacio() {
        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validar(
                        archivo("oficio.pdf", new byte[0])
                )
        );

        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void rechazaExtensionDistintaAunqueElContenidoSeaPdf()
            throws IOException {

        byte[] contenido = crearPdf();

        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validar(
                        archivo("oficio.txt", contenido)
                )
        );

        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void rechazaTextoRenombradoComoPdf() {
        byte[] contenido = "Esto no es un PDF"
                .getBytes(StandardCharsets.UTF_8);

        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validar(
                        archivo("oficio.pdf", contenido)
                )
        );

        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void rechazaCabeceraPdfSinDocumentoValido() {
        byte[] contenido = "%PDF-1.7\ncontenido incompleto"
                .getBytes(StandardCharsets.US_ASCII);

        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validar(
                        archivo("oficio.pdf", contenido)
                )
        );

        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void rechazaArchivoQueSuperaTresMb() {
        byte[] contenido =
                new byte[ReservaPdfValidacionService.MAX_BYTES + 1];

        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validar(
                        archivo("oficio.pdf", contenido)
                )
        );

        assertEquals(413, error.getStatusCode().value());
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
