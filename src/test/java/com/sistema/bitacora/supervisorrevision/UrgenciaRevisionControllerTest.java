package com.sistema.bitacora.supervisorrevision;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class UrgenciaRevisionControllerTest {
    @Test void businessConflictIsExplicitlyNotApplied() {var response=new UrgenciaRevisionController(null).business(new ResponseStatusException(HttpStatus.CONFLICT,"Cambió"));assertEquals(409,response.getStatusCode().value());assertFalse(response.getBody().resultadoIncierto());}
    @Test void unknownFailureIsAmbiguousAndSanitized() {var response=new UrgenciaRevisionController(null).unknown(new IllegalStateException("jdbc password private path"));assertEquals(503,response.getStatusCode().value());assertTrue(response.getBody().resultadoIncierto());assertFalse(response.getBody().mensaje().contains("password"));}
    @Test void freshCsrfChecksAccessAndUsesCurrentTokenWithoutCaching() {var service=mock(UrgenciaRevisionService.class);var response=new UrgenciaRevisionController(service).csrf(new DefaultCsrfToken("X-CSRF-TOKEN","_csrf","current-session-token"));verify(service).checkAccess();assertEquals("X-CSRF-TOKEN",response.getBody().headerName());assertEquals("current-session-token",response.getBody().token());assertEquals("no-store",response.getHeaders().getCacheControl());}
}