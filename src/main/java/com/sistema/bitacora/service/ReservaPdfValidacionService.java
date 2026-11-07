package com.sistema.bitacora.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

@Service
public class ReservaPdfValidacionService {

    public static final int MAX_BYTES = 3_000_000;

    // Mantiene la compatibilidad con la comprobación del formulario.
    public ResultadoPdf validar(MultipartFile archivo) {
        return validarParaGuardar(archivo).resultado();
    }

    public PdfValidado validarParaGuardar(MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Selecciona un documento PDF que no esté vacío"
            );
        }

        if (archivo.getSize() > MAX_BYTES) {
            throw error(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "Cada documento puede pesar como máximo 3 MB"
            );
        }

        String nombre = archivo.getOriginalFilename();

        if (nombre == null
                || !nombre.trim().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "Solo se permiten archivos con extensión PDF"
            );
        }

        byte[] contenido;

        // Lee el archivo una sola vez, con un límite.
        try (InputStream entrada = archivo.getInputStream()) {
            contenido = entrada.readNBytes(MAX_BYTES + 1);
        } catch (IOException ex) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "No se pudo leer el documento. "
                            + "Intenta seleccionarlo otra vez"
            );
        }

        if (contenido.length > MAX_BYTES) {
            throw error(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "Cada documento puede pesar como máximo 3 MB"
            );
        }

        if (!tieneCabeceraPdf(contenido)) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "El contenido del archivo no corresponde a un PDF"
            );
        }

        ResultadoPdf resultado;

        try (PDDocument documento = Loader.loadPDF(contenido)) {
            int paginas = documento.getNumberOfPages();

            if (paginas < 1) {
                throw error(
                        HttpStatus.BAD_REQUEST,
                        "El documento PDF debe contener al menos una página"
                );
            }

            resultado = new ResultadoPdf(contenido.length, paginas);

        } catch (InvalidPasswordException ex) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "No se puede comprobar un PDF que exige contraseña. "
                            + "Adjunta una copia que pueda abrirse sin ella"
            );
        } catch (IOException | IllegalArgumentException ex) {
            throw error(
                    HttpStatus.BAD_REQUEST,
                    "No se pudo abrir el documento como PDF. "
                            + "Comprueba que el archivo no esté dañado"
            );
        }

        return new PdfValidado(contenido, resultado);
    }

    private boolean tieneCabeceraPdf(byte[] contenido) {
        return contenido.length >= 5
                && contenido[0] == '%'
                && contenido[1] == 'P'
                && contenido[2] == 'D'
                && contenido[3] == 'F'
                && contenido[4] == '-';
    }

    private ResponseStatusException error(
            HttpStatus estado,
            String mensaje) {
        return new ResponseStatusException(estado, mensaje);
    }

    public record ResultadoPdf(
            int tamanioBytes,
            int paginas) {
    }

    /**
     * Solo el validador puede construir esta instancia.
     * No expone el arreglo interno para que otros puedan modificarlo.
     */
    public static final class PdfValidado {

        private final byte[] contenido;
        private final ResultadoPdf resultado;

        private PdfValidado(
                byte[] contenido,
                ResultadoPdf resultado) {
            this.contenido = contenido;
            this.resultado = resultado;
        }

        public ResultadoPdf resultado() {
            return resultado;
        }

        public byte[] copiarContenido() {
            return contenido.clone();
        }
    }
}
