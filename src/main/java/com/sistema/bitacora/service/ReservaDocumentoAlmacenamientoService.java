package com.sistema.bitacora.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Service
public class ReservaDocumentoAlmacenamientoService {

    private static final Logger log = LoggerFactory.getLogger(
            ReservaDocumentoAlmacenamientoService.class
    );

    private final Path directorio;
    private final ReservaPdfValidacionService pdfService;

    public ReservaDocumentoAlmacenamientoService(
            @Value("${bitacora.documentos.directorio}")
            String directorio,
            ReservaPdfValidacionService pdfService) {

        if (directorio == null || directorio.isBlank()) {
            throw new IllegalArgumentException(
                    "Configura el directorio privado de documentos"
            );
        }

        this.directorio = Path.of(directorio)
                .toAbsolutePath()
                .normalize();

        this.pdfService = pdfService;
    }

    /**
     * Método previsto para la futura confirmación de reservas.
     * Exige una transacción real de escritura y sincronización activa.
     *
     * No debe utilizarse desde la comprobación parcial.
     */
    public DocumentoGuardado guardarEnTransaccion(MultipartFile archivo) {
        comprobarTransaccion();

        DocumentoGuardado documento = guardar(archivo);

        try {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) {
                                limpiarTrasRollback(documento);
                            } else if (status != STATUS_COMMITTED) {
                                log.warn(
                                        "Resultado de transaccion desconocido. "
                                                + "Se conserva el documento {} "
                                                + "para revision.",
                                        documento.referencia()
                                );
                            }
                        }
                    }
            );
        } catch (RuntimeException ex) {
            // Si no se pudo registrar la limpieza, no dejamos
            // silenciosamente el archivo recién creado.
            try {
                eliminarDocumentoCreado(documento);
            } catch (IOException | SecurityException limpieza) {
                ex.addSuppressed(limpieza);
            }

            throw ex;
        }

        return documento;
    }

    private void comprobarTransaccion() {
        if (!TransactionSynchronizationManager
                .isActualTransactionActive()
                || !TransactionSynchronizationManager
                .isSynchronizationActive()
                || TransactionSynchronizationManager
                .isCurrentTransactionReadOnly()) {

            throw new IllegalStateException(
                    "El guardado de documentos de una reserva requiere "
                            + "una transaccion activa de escritura"
            );
        }
    }

    /**
     * Escritura de bajo nivel, conservada para las pruebas existentes.
     * La futura confirmación de reservas usará guardarEnTransaccion().
     */
    public DocumentoGuardado guardar(MultipartFile archivo) {
        var pdf = pdfService.validarParaGuardar(archivo);
        byte[] contenido = pdf.copiarContenido();

        String referencia = UUID.randomUUID() + ".pdf";
        Path destino = directorio.resolve(referencia);

        boolean creado = false;

        try {
            Files.createDirectories(directorio);
            comprobarDirectorio();

            try (OutputStream salida = Files.newOutputStream(
                    destino,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {

                creado = true;
                salida.write(contenido);
            }

            return new DocumentoGuardado(
                    referencia,
                    pdf.resultado().tamanioBytes(),
                    pdf.resultado().paginas()
            );

        } catch (IOException | SecurityException ex) {
            if (creado) {
                try {
                    Files.deleteIfExists(destino);
                } catch (IOException | SecurityException limpieza) {
                    ex.addSuppressed(limpieza);
                }
            }

            throw new IllegalStateException(
                    "No se pudo guardar el documento "
                            + "en el almacenamiento privado",
                    ex
            );
        }
    }

    private void comprobarDirectorio() throws IOException {
        if (Files.isSymbolicLink(directorio)
                || !Files.isDirectory(
                directorio,
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "El directorio configurado no es una carpeta valida"
            );
        }
    }

    private void limpiarTrasRollback(DocumentoGuardado documento) {
        try {
            eliminarDocumentoCreado(documento);
        } catch (IOException | SecurityException ex) {
            // No ocultamos el fallo: queda referencia para revisión.
            // La transacción ya terminó; esto no revierte su resultado.
            log.error(
                    "No se pudo retirar el documento {} tras rollback. "
                            + "Requiere revision del almacenamiento.",
                    documento.referencia(),
                    ex
            );
        }
    }

    /**
     * Es privado: no acepta solicitudes de borrado desde el navegador.
     * Solo recibe documentos creados por esta operación de guardado.
     */
    private void eliminarDocumentoCreado(
            DocumentoGuardado documento) throws IOException {

        if (!Files.exists(directorio, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        comprobarDirectorio();

        Path destino = directorio
                .resolve(documento.referencia())
                .normalize();

        if (!directorio.equals(destino.getParent())) {
            throw new IOException(
                    "La referencia del documento no pertenece al directorio"
            );
        }

        if (Files.isSymbolicLink(destino)) {
            throw new IOException(
                    "No se permite retirar un enlace simbolico"
            );
        }

        if (Files.exists(destino, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(
                destino,
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "El destino no es un archivo regular"
            );
        }

        Files.deleteIfExists(destino);
    }

    public record DocumentoGuardado(
            String referencia,
            int tamanioBytes,
            int paginas) {
    }
}