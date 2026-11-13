package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.EspacioEliminacionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Unitarias de entrada y resultado. NO sustituyen pruebas reales HTTP/CSRF.
class EspacioEliminacionWebControllerTest {
    private EspacioEliminacionService servicio;
    private EspacioEliminacionWebController controlador;
    private MultiValueMap<String, String> datos;
    private RedirectAttributesModelMap atributos;

    @BeforeEach void preparar() {
        servicio = mock(EspacioEliminacionService.class);
        controlador = new EspacioEliminacionWebController(servicio);
        datos = new LinkedMultiValueMap<>();
        datos.add("id", "20"); datos.add("confirmacion", "ELIMINAR");
        atributos = new RedirectAttributesModelMap();
    }
    private void invalido() {
        assertEquals("redirect:/admin/dashboard", controlador.eliminar(datos, atributos));
        assertTrue(atributos.getFlashAttributes().containsKey("errorEspacio"));
        verifyNoInteractions(servicio);
    }
    @Test void confirmaYRedirige() {
        assertEquals("redirect:/admin/dashboard", controlador.eliminar(datos, atributos));
        verify(servicio).eliminar(20L);
        assertTrue(atributos.getFlashAttributes().containsKey("exitoEspacio"));
    }
    @Test void exigeConfirmacion() { datos.remove("confirmacion"); invalido(); }
    @Test void rechazaConfirmacionIncorrecta() { datos.set("confirmacion", "SI"); invalido(); }
    @Test void rechazaConfirmacionDuplicada() { datos.add("confirmacion", "ELIMINAR"); invalido(); }
    @Test void rechazaIdDuplicado() { datos.add("id", "21"); invalido(); }
    @Test void rechazaIdAusente() { datos.remove("id"); invalido(); }
    @Test void rechazaIdNoNumerico() { datos.set("id", "20 OR 1=1"); invalido(); }
    @Test void rechazaIdNegativo() { datos.set("id", "-20"); invalido(); }
    @Test void rechazaDesbordamientoLong() { datos.set("id", "9999999999999999999"); invalido(); }
    @Test void comunicaConflictoSinMensajeDeExito() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Usa Desactivar"))
                .when(servicio).eliminar(20L);
        controlador.eliminar(datos, atributos);
        assertEquals("Usa Desactivar", atributos.getFlashAttributes().get("errorEspacio"));
        assertFalse(atributos.getFlashAttributes().containsKey("exitoEspacio"));
    }
    @Test void noExponeDetallesDeFalloNiAseguraResultado() {
        doThrow(new IllegalStateException("SQL secreto")) .when(servicio).eliminar(20L);
        controlador.eliminar(datos, atributos);
        String mensaje = atributos.getFlashAttributes().get("errorEspacio").toString();
        assertTrue(mensaje.contains("No se pudo confirmar"));
        assertFalse(mensaje.contains("SQL secreto"));
        assertFalse(atributos.getFlashAttributes().containsKey("exitoEspacio"));
    }
}
