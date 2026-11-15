package com.sistema.bitacora.supervisorrevision;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class UrgenciaDocumentosTest {
    @TempDir Path root;
    private final String ref="00000000-0000-4000-8000-000000000001.pdf";
    private byte[] pdf() throws IOException {try(PDDocument p=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){p.addPage(new PDPage());p.save(out);return out.toByteArray();}}
    @Test void validPdfReadWithoutModification() throws Exception {byte[] b=pdf();Files.write(root.resolve(ref),b);assertArrayEquals(b,new UrgenciaDocumentos(root.toString()).read(ref));assertArrayEquals(b,Files.readAllBytes(root.resolve(ref)));}
    @Test void traversalRejected() {assertThrows(ResponseStatusException.class,()->new UrgenciaDocumentos(root.toString()).read("../"+ref));}
    @Test void missingRejectedWithoutCreating() {assertThrows(ResponseStatusException.class,()->new UrgenciaDocumentos(root.toString()).read(ref));assertFalse(Files.exists(root.resolve(ref)));}
    @Test void oversizeRejected() throws Exception {Files.write(root.resolve(ref),new byte[3_000_001]);assertThrows(ResponseStatusException.class,()->new UrgenciaDocumentos(root.toString()).read(ref));}
    @Test void htmlRejected() throws Exception {Files.writeString(root.resolve(ref),"<html>not a PDF</html>");assertThrows(ResponseStatusException.class,()->new UrgenciaDocumentos(root.toString()).read(ref));}
    @Test void directoryRejected() throws Exception {Files.createDirectory(root.resolve(ref));assertThrows(ResponseStatusException.class,()->new UrgenciaDocumentos(root.toString()).read(ref));}
    @Test void malformedPdfRejected() throws Exception {Files.writeString(root.resolve(ref),"%PDF-invalid");assertThrows(ResponseStatusException.class,()->new UrgenciaDocumentos(root.toString()).read(ref));}
}