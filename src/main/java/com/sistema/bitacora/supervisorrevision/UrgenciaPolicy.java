package com.sistema.bitacora.supervisorrevision;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;

public final class UrgenciaPolicy {
    private UrgenciaPolicy() {}
    public record Command(String clave, String versionEsperada, String accion,
                          String motivo, boolean documentosRevisados) {}
    public record Transition(String estado, String documentos, boolean reintegrar) {}
    public static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    public static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    public static Command validate(Command c) {
        if (c == null) throw bad("Falta la operación");
        try {
            if (c.clave() == null || !UUID.fromString(c.clave()).toString().equals(c.clave())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw bad("La clave debe ser un UUID canónico"); }
        if (c.versionEsperada() == null || !c.versionEsperada().matches("[0-9a-f]{64}")) throw bad("Falta una versión válida");
        if (c.accion() == null || !Set.of("APROBAR","RECHAZAR","VALIDAR_DOCUMENTOS","OBSERVAR_DOCUMENTOS").contains(c.accion())) throw bad("Acción no válida");
        if (c.motivo() == null || c.motivo().isBlank() || c.motivo().length() > 255 || c.motivo().indexOf('\0') >= 0) throw bad("Indica un motivo de 1 a 255 caracteres");
        if (!"OBSERVAR_DOCUMENTOS".equals(c.accion()) && !c.documentosRevisados()) throw bad("Confirma la revisión manual de los documentos");
        return c; // Exact payload, no silent normalization on replay.
    }
    public static boolean urgent(String mode) { return "URGENTE_REQUISICION".equals(mode) || "URGENTE_INVENTARIO".equals(mode); }
    public static Transition transition(String mode, String state, Command c) {
        if (!urgent(mode)) throw conflict("La modalidad no pertenece a este módulo");
        return switch (c.accion()) {
            case "APROBAR" -> {
                pendingRequisition(mode,state); yield new Transition("APROBADA","VALIDADA",false);
            }
            case "RECHAZAR" -> {
                pendingRequisition(mode,state); yield new Transition("RECHAZADA","NO_APLICA",true);
            }
            case "VALIDAR_DOCUMENTOS", "OBSERVAR_DOCUMENTOS" -> {
                if (!"APROBADA".equals(state)) throw conflict("Solo se revisan documentos de reservas aprobadas");
                yield new Transition(state, "VALIDAR_DOCUMENTOS".equals(c.accion()) ? "VALIDADA" : "OBSERVADA", false);
            }
            default -> throw bad("Acción no válida");
        };
    }
    private static void pendingRequisition(String mode, String state) {
        if (!"URGENTE_REQUISICION".equals(mode) || !"PENDIENTE".equals(state)) throw conflict("Solo puede dictaminarse una requisición urgente pendiente");
    }
    public static void checkVersion(String expected, String actual) {
        if (!Objects.equals(expected,actual)) throw conflict("La reserva cambió; vuelve a consultar antes de decidir");
    }
    public static void checkReplay(long request, long actor, String signature, long savedRequest, long savedActor, String savedSignature) {
        if (request != savedRequest || actor != savedActor || !Objects.equals(signature,savedSignature)) throw conflict("La clave ya se utilizó con otros datos o por otra cuenta");
    }
    public static String signature(long id, long actor, Command c) {
        return hash("operation-v1", id, actor, c.clave(), c.versionEsperada(), c.accion(), c.motivo(), c.documentosRevisados());
    }
    public static String hash(Object... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                byte[] b = (part == null ? "" : part.toString()).getBytes(StandardCharsets.UTF_8);
                md.update(new byte[]{(byte)(b.length>>>24),(byte)(b.length>>>16),(byte)(b.length>>>8),(byte)b.length});
                md.update(b);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static int requireCleaning(Integer minutes) {
        if (minutes == null || minutes <= 0 || minutes > 10080) throw conflict("El tiempo de limpieza debe ser positivo y no superar 10080 minutos");
        return minutes;
    }
    public static boolean overlap(LocalDateTime start, LocalDateTime end, LocalDateTime otherStart, LocalDateTime otherEnd, Integer cleaning) {
        int minutes = requireCleaning(cleaning);
        return start.isBefore(otherEnd.plusMinutes(minutes)) && otherStart.isBefore(end.plusMinutes(minutes));
    }
}