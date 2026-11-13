package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.InventarioEliminacionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class InventarioEliminacionWebController {
    private static final Logger log = LoggerFactory.getLogger(InventarioEliminacionWebController.class);
    private final InventarioEliminacionService servicio;
    public InventarioEliminacionWebController(InventarioEliminacionService servicio) { this.servicio = servicio; }

    // Solo POST, conservando la cadena web existente con sesion, autorizacion y CSRF.
    @PostMapping("/admin/espacios/{espacioId}/inventario/{articuloId}/eliminar")
    public String eliminar(@PathVariable("espacioId") Long espacioId,
            @PathVariable("articuloId") Long articuloId,
            @RequestParam MultiValueMap<String, String> datos, RedirectAttributes atributos) {
        if (espacioId == null || espacioId <= 0 || articuloId == null || articuloId <= 0) {
            atributos.addFlashAttribute("errorEspacio", "Identificador de espacio o articulo invalido");
            return "redirect:/admin/dashboard";
        }
        String destino = "redirect:/admin/espacios/" + espacioId + "/inventario";
        var confirmaciones = datos.get("confirmacion");
        if (confirmaciones == null || confirmaciones.size() != 1
                || !"ELIMINAR".equals(confirmaciones.getFirst())) {
            atributos.addFlashAttribute("error", "Marca la confirmacion del articulo que deseas eliminar");
            return destino;
        }
        try {
            servicio.eliminar(espacioId, articuloId);
            atributos.addFlashAttribute("exito", "Articulo eliminado. Si el espacio quedo vacio y sin reservas, "
                    + "puedes volver a espacios y eliminarlo");
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == 404) {
                atributos.addFlashAttribute("errorEspacio", ex.getReason());
                return "redirect:/admin/dashboard";
            }
            atributos.addFlashAttribute("error", ex.getReason());
        } catch (RuntimeException ex) {
            log.warn("No se pudo confirmar la eliminacion del articulo {} del espacio {}. Tipo: {}",
                    articuloId, espacioId, ex.getClass().getSimpleName());
            atributos.addFlashAttribute("error", "No se pudo confirmar la eliminacion. "
                    + "Actualiza la pagina antes de intentarlo de nuevo. No fuerces el borrado desde la base de datos");
        }
        return destino;
    }
}
