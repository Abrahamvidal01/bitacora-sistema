package com.sistema.bitacora.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
        directorio = temporal.resolve("documentos");

        servicio = new ReservaDocumentoAlmacenamientoService(
                directorio.toString(),
                new ReservaPdfValidacionService()
        );
    }

    @AfterEach
    void limpiarEstadoDePrueba() {
        limpiarEstadoTransaccion();
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

    @Test
    void rechazaGuardadoTransaccionalSinTransaccion() {
        assertThrows(
                IllegalStateException.class,
                () -> servicio.guardarEnTransaccion(
                        archivo("oficio.pdf", new byte[]{1})
                )
        );

        assertFalse(Files.exists(directorio));
    }

    @Test
    void rechazaTransaccionDeSoloLectura() {
        iniciarTransaccionSimulada(true);

        assertThrows(
                IllegalStateException.class,
                () -> servicio.guardarEnTransaccion(
                        archivo("oficio.pdf", new byte[]{1})
                )
        );

        assertFalse(Files.exists(directorio));
        assertTrue(
                TransactionSynchronizationManager
                        .getSynchronizations()
                        .isEmpty()
        );
    }

    @Test
    void rechazaTransaccionSinSincronizacionActiva() {
        TransactionSynchronizationManager
                .setActualTransactionActive(true);

        assertThrows(
                IllegalStateException.class,
                () -> servicio.guardarEnTransaccion(
                        archivo("oficio.pdf", new byte[]{1})
                )
        );

        assertFalse(Files.exists(directorio));
    }

    @Test
    void rollbackRetiraSoloLosDocumentosDelIntento() throws IOException {
        byte[] contenido = crearPdf();

        // Documento previo, ajeno al intento que va a fallar.
        var anterior = servicio.guardar(
                archivo("anterior.pdf", contenido)
        );

        iniciarTransaccionSimulada(false);

        var oficio = servicio.guardarEnTransaccion(
                archivo("oficio.pdf", contenido)
        );

        var requisicion = servicio.guardarEnTransaccion(
                archivo("requisicion.pdf", contenido)
        );

        assertTrue(Files.exists(
                directorio.resolve(oficio.referencia())
        ));

        assertTrue(Files.exists(
                directorio.resolve(requisicion.referencia())
        ));

        assertEquals(
                2,
                TransactionSynchronizationManager
                        .getSynchronizations()
                        .size()
        );

        completarTransaccionSimulada(
                TransactionSynchronization.STATUS_ROLLED_BACK
        );

        assertFalse(Files.exists(
                directorio.resolve(oficio.referencia())
        ));

        assertFalse(Files.exists(
                directorio.resolve(requisicion.referencia())
        ));

        assertArrayEquals(
                contenido,
                Files.readAllBytes(
                        directorio.resolve(anterior.referencia())
                )
        );

        try (var archivos = Files.list(directorio)) {
            assertEquals(1L, archivos.count());
        }
    }

    @Test
    void commitConservaElDocumento() throws IOException {
        byte[] contenido = crearPdf();

        iniciarTransaccionSimulada(false);

        var documento = servicio.guardarEnTransaccion(
                archivo("oficio.pdf", contenido)
        );

        completarTransaccionSimulada(
                TransactionSynchronization.STATUS_COMMITTED
        );

        assertArrayEquals(
                contenido,
                Files.readAllBytes(
                        directorio.resolve(documento.referencia())
                )
        );
    }

    @Test
    void resultadoDesconocidoNoBorraElDocumento() throws IOException {
        byte[] contenido = crearPdf();

        iniciarTransaccionSimulada(false);

        var documento = servicio.guardarEnTransaccion(
                archivo("oficio.pdf", contenido)
        );

        completarTransaccionSimulada(
                TransactionSynchronization.STATUS_UNKNOWN
        );

        assertArrayEquals(
                contenido,
                Files.readAllBytes(
                        directorio.resolve(documento.referencia())
                )
        );
    }

    // Simula indicadores y callbacks de Spring.
    // No abre una transacción real de MariaDB.
    private void iniciarTransaccionSimulada(boolean soloLectura) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager
                .setActualTransactionActive(true);
        TransactionSynchronizationManager
                .setCurrentTransactionReadOnly(soloLectura);
    }

    private void completarTransaccionSimulada(int resultado) {
        var sincronizaciones =
                TransactionSynchronizationManager.getSynchronizations();

        try {
            for (var sincronizacion : sincronizaciones) {
                sincronizacion.afterCompletion(resultado);
            }
        } finally {
            limpiarEstadoTransaccion();
        }
    }

    private void limpiarEstadoTransaccion() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager
                .setActualTransactionActive(false);

        TransactionSynchronizationManager
                .setCurrentTransactionReadOnly(false);
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