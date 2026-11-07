package com.sistema.bitacora.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
     * Prepara una operación de almacenamiento.
     * No debe llamarse desde la comprobación parcial del formulario.
     */
    public DocumentoGuardado guardar(MultipartFile archivo) {
        // Valida antes de crear carpetas o archivos.
        var pdf = pdfService.validarParaGuardar(archivo);

        // Son los mismos bytes que el validador abrió y comprobó.
        byte[] contenido = pdf.copiarContenido();

        String referencia = UUID.randomUUID() + ".pdf";
        Path destino = directorio.resolve(referencia);

        boolean creado = false;

        try {
            Files.createDirectories(directorio);

            if (Files.isSymbolicLink(directorio)
                    || !Files.isDirectory(
                    directorio,
                    LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "El directorio configurado no es una carpeta válida"
                );
            }

            // CREATE_NEW falla si el nombre ya existe.
            // Nunca reemplaza un documento anterior.
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
            // Si la escritura falló después de crear el archivo,
            // intenta retirar únicamente ese archivo incompleto.
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

    /**
     * Devuelve una referencia interna, no una URL pública
     * ni una ruta absoluta de la computadora.
     */
    public record DocumentoGuardado(
            String referencia,
            int tamanioBytes,
            int paginas) {
    }
}