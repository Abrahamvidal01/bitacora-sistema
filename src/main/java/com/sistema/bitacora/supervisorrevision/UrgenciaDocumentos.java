package com.sistema.bitacora.supervisorrevision;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.Set;

@Service
@ConditionalOnProperty(name="bitacora.supervisor.revision-habilitada", havingValue="true")
public class UrgenciaDocumentos {
    static final int MAX = 3_000_000;
    private final Path root;
    public UrgenciaDocumentos(@Value("${bitacora.documentos.directorio}") String root) {
        if (root == null || root.isBlank()) throw new IllegalArgumentException("Falta el directorio privado");
        this.root = Path.of(root).toAbsolutePath().normalize();
    }
    public byte[] read(String reference) {
        if (reference == null || !reference.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf")) throw unavailable();
        try {
            for (Path p = root; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IOException();
            if (!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            Path path = root.resolve(reference).normalize();
            if (!root.equals(path.getParent()) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            byte[] bytes;
            try (SeekableByteChannel channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                if (channel.size() <= 0 || channel.size() > MAX) throw new IOException();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                int total = 0, n;
                while ((n = channel.read(buffer)) != -1) {
                    total += n; if (total > MAX) throw new IOException();
                    output.write(buffer.array(),0,n); buffer.clear();
                }
                bytes = output.toByteArray();
            }
            if (bytes.length < 5 || bytes[0]!='%' || bytes[1]!='P' || bytes[2]!='D' || bytes[3]!='F' || bytes[4]!='-') throw new IOException();
            try (PDDocument pdf = Loader.loadPDF(bytes)) {
                if (pdf.isEncrypted() || pdf.getNumberOfPages() < 1) throw new IOException();
            }
            return bytes;
        } catch (IOException | SecurityException | IllegalArgumentException e) { throw unavailable(); }
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.CONFLICT,"Documento no disponible o PDF no legible; no se aplicó la decisión");
    }
}