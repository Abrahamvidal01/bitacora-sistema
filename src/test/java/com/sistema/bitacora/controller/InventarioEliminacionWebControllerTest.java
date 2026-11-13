package com.sistema.bitacora.controller;
import com.sistema.bitacora.service.InventarioEliminacionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Unitarias de entrada y resultado; no equivalen a pruebas HTTP/CSRF reales.
class InventarioEliminacionWebControllerTest {
    private InventarioEliminacionService servicio;
    private InventarioEliminacionWebController controlador;
    private MultiValueMap<String, String> datos;
    private RedirectAttributesModelMap atributos;
    private static final String DESTINO = "redirect:/admin/espacios/20/inventario";
    @BeforeEach void preparar() {
        servicio = mock(InventarioEliminacionService.class); controlador = new InventarioEliminacionWebController(servicio);
        datos = new LinkedMultiValueMap<>(); datos.add("confirmacion", "ELIMINAR"); atributos = new RedirectAttributesModelMap();
    }
    @Test void eliminaConConfirmacionYRedirige() {
        assertEquals(DESTINO, controlador.eliminar(20L, 30L, datos, atributos)); verify(servicio).eliminar(20L, 30L);
        assertTrue(atributos.getFlashAttributes().containsKey("exito"));
    }
    @Test void exigeConfirmacion() {
        datos.clear(); assertEquals(DESTINO, controlador.eliminar(20L, 30L, datos, atributos));
        assertTrue(atributos.getFlashAttributes().containsKey("error")); verifyNoInteractions(servicio);
    }
    @Test void rechazaConfirmacionDuplicada() {
        datos.add("confirmacion", "ELIMINAR"); controlador.eliminar(20L, 30L, datos, atributos);
        assertTrue(atributos.getFlashAttributes().containsKey("error")); verifyNoInteractions(servicio);
    }
    @Test void rechazaConfirmacionIncorrecta() {
        datos.set("confirmacion", "SI"); controlador.eliminar(20L, 30L, datos, atributos);
        assertTrue(atributos.getFlashAttributes().containsKey("error")); verifyNoInteractions(servicio);
    }
    @Test void rechazaIdsInvalidos() {
        assertEquals("redirect:/admin/dashboard", controlador.eliminar(-1L, 30L, datos, atributos));
        assertTrue(atributos.getFlashAttributes().containsKey("errorEspacio")); verifyNoInteractions(servicio);
    }
    @Test void comunicaConflictoSinExito() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Tiene historial")).when(servicio).eliminar(20L, 30L);
        assertEquals(DESTINO, controlador.eliminar(20L, 30L, datos, atributos));
        assertEquals("Tiene historial", atributos.getFlashAttributes().get("error"));
        assertFalse(atributos.getFlashAttributes().containsKey("exito"));
    }
    @Test void noReintentaNiExponeDetallesSiFalla() {
        doThrow(new IllegalStateException("SQL secreto")).when(servicio).eliminar(20L, 30L);
        controlador.eliminar(20L, 30L, datos, atributos);
        String mensaje = atributos.getFlashAttributes().get("error").toString();
        assertTrue(mensaje.contains("No se pudo confirmar")); assertFalse(mensaje.contains("SQL secreto"));
        assertFalse(atributos.getFlashAttributes().containsKey("exito")); verify(servicio, times(1)).eliminar(20L, 30L);
    }
    @Test void regresaAlDashboardSiElRegistroYaNoExiste() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe")).when(servicio).eliminar(20L, 30L);
        assertEquals("redirect:/admin/dashboard", controlador.eliminar(20L, 30L, datos, atributos));
        assertTrue(atributos.getFlashAttributes().containsKey("errorEspacio"));
        assertFalse(atributos.getFlashAttributes().containsKey("exito"));
    }
}
