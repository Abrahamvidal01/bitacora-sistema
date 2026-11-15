package com.sistema.bitacora.supervisorrevision;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
class UrgenciaPolicyTest {
    private UrgenciaPolicy.Command command(String action) {return new UrgenciaPolicy.Command("00000000-0000-4000-8000-000000000001","a".repeat(64),action,"Revisión manual",true);}
    @Test void validCommand() {assertEquals(command("APROBAR"),UrgenciaPolicy.validate(command("APROBAR")));}
    @Test void invalidUuid() {assertThrows(ResponseStatusException.class,()->UrgenciaPolicy.validate(new UrgenciaPolicy.Command("x","a".repeat(64),"APROBAR","ok",true)));}
    @Test void blankReason() {assertThrows(ResponseStatusException.class,()->UrgenciaPolicy.validate(new UrgenciaPolicy.Command(command("APROBAR").clave(),"a".repeat(64),"APROBAR"," ",true)));}
    @Test void oversizedReason() {assertThrows(ResponseStatusException.class,()->UrgenciaPolicy.validate(new UrgenciaPolicy.Command(command("APROBAR").clave(),"a".repeat(64),"APROBAR","x".repeat(256),true)));}
    @Test void manualReviewRequired() {assertThrows(ResponseStatusException.class,()->UrgenciaPolicy.validate(new UrgenciaPolicy.Command(command("APROBAR").clave(),"a".repeat(64),"APROBAR","ok",false)));}
    @Test void staleVersion() {assertEquals(409,assertThrows(ResponseStatusException.class,()->UrgenciaPolicy.checkVersion("a","b")).getStatusCode().value());}
    @Test void exactReplayAccepted() {assertDoesNotThrow(()->UrgenciaPolicy.checkReplay(1,2,"hash",1,2,"hash"));}
    @Test void changedReplayRejected() {assertThrows(ResponseStatusException.class,()->UrgenciaPolicy.checkReplay(1,2,"hash",1,2,"changed"));}
    @Test void otherActorReplayRejected() {assertThrows(ResponseStatusException.class,()->UrgenciaPolicy.checkReplay(1,2,"hash",1,3,"hash"));}
    @Test void rejectOnlyPendingRequisitionAndRefund() {assertTrue(UrgenciaPolicy.transition("URGENTE_REQUISICION","PENDIENTE",command("RECHAZAR")).reintegrar()); assertThrows(ResponseStatusException.class,()->UrgenciaPolicy.transition("URGENTE_INVENTARIO","APROBADA",command("RECHAZAR")));}
    @Test void normalExcluded() {assertFalse(UrgenciaPolicy.urgent("NORMAL"));assertThrows(ResponseStatusException.class,()->UrgenciaPolicy.transition("NORMAL","PENDIENTE",command("APROBAR")));}
    @Test void touchingAfterCleaningAllowed() {LocalDateTime a=LocalDateTime.of(2026,11,16,10,0);assertFalse(UrgenciaPolicy.overlap(a,a.plusHours(2),a.plusHours(2).plusMinutes(30),a.plusHours(3),30));}
    @Test void cleaningConflictsBothDirections() {LocalDateTime a=LocalDateTime.of(2026,11,16,10,0);assertTrue(UrgenciaPolicy.overlap(a,a.plusHours(2),a.plusHours(2).plusMinutes(29),a.plusHours(3),30));assertTrue(UrgenciaPolicy.overlap(a.plusHours(2).plusMinutes(29),a.plusHours(3),a,a.plusHours(2),30));}
    @Test void signatureIncludesActorAndExactReason() {var a=command("APROBAR");var b=new UrgenciaPolicy.Command(a.clave(),a.versionEsperada(),a.accion(),a.motivo()+" ",true);assertNotEquals(UrgenciaPolicy.signature(1,2,a),UrgenciaPolicy.signature(1,2,b));assertNotEquals(UrgenciaPolicy.signature(1,2,a),UrgenciaPolicy.signature(1,3,a));}
    @Test void observationDoesNotCancelOrRefund() {var result=UrgenciaPolicy.transition("URGENTE_INVENTARIO","APROBADA",command("OBSERVAR_DOCUMENTOS"));assertEquals("APROBADA",result.estado());assertEquals("OBSERVADA",result.documentos());assertFalse(result.reintegrar());}
    @Test void approvalDoesNotConsumeAgain() {var result=UrgenciaPolicy.transition("URGENTE_REQUISICION","PENDIENTE",command("APROBAR"));assertEquals("VALIDADA",result.documentos());assertFalse(result.reintegrar());}
    @Test void nullCleaningRejectedEvenWithoutOverlap() {LocalDateTime a=LocalDateTime.of(2026,11,16,10,0);assertEquals(409,assertThrows(ResponseStatusException.class,()->UrgenciaPolicy.overlap(a,a.plusHours(1),a.plusDays(1),a.plusDays(1).plusHours(1),null)).getStatusCode().value());}
    @Test void zeroCleaningRejectedEvenWithoutOverlap() {LocalDateTime a=LocalDateTime.of(2026,11,16,10,0);assertEquals(409,assertThrows(ResponseStatusException.class,()->UrgenciaPolicy.overlap(a,a.plusHours(1),a.plusDays(1),a.plusDays(1).plusHours(1),0)).getStatusCode().value());}
    @Test void negativeCleaningRejectedEvenWithoutOverlap() {LocalDateTime a=LocalDateTime.of(2026,11,16,10,0);assertEquals(409,assertThrows(ResponseStatusException.class,()->UrgenciaPolicy.overlap(a,a.plusHours(1),a.plusDays(1),a.plusDays(1).plusHours(1),-1)).getStatusCode().value());}
}