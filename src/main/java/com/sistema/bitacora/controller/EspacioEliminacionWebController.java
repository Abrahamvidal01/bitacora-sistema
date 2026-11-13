package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.EspacioEliminacionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class EspacioEliminacionWebController {
    private static final Logger log = LoggerFactory.getLogger(EspacioEliminacionWebController.class);
    private final EspacioEliminacionService servicio;

    public EspacioEliminacionWebController(EspacioEliminacionService servicio) {
        this.servicio = servicio;
    }

    // Solo POST. La cadena web existente mantiene sesion, autorizacion y CSRF.
    @PostMapping("/admin/espacios/eliminar")
    public String eliminar(@RequestParam MultiValueMap<String, String> datos,
            RedirectAttributes atributos) {
        Long id;
        try {
            String texto = unico(datos, "id");
            if (!texto.matches("[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException();
            }
            id = Long.valueOf(texto);
            if (!"ELIMINAR".equals(unico(datos, "confirmacion"))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            atributos.addFlashAttribute("errorEspacio",
                    "Selecciona un espacio y marca la confirmacion para eliminarlo");
            return "redirect:/admin/dashboard";
        }
        try {
            servicio.eliminar(id);
            atributos.addFlashAttribute("exitoEspacio", "Espacio eliminado correctamente");
        } catch (ResponseStatusException ex) {
            atributos.addFlashAttribute("errorEspacio", ex.getReason());
        } catch (RuntimeException ex) {
            // No exponer SQL, rutas o detalles de conexion al navegador.
            log.warn("No se pudo confirmar la eliminacion del espacio {}. Tipo: {}",
                    id, ex.getClass().getSimpleName());
            atributos.addFlashAttribute("errorEspacio",
                    "No se pudo confirmar la eliminacion. Actualiza la pagina antes de intentarlo de nuevo. "
                    + "No borres reservas ni inventario para forzarla");
        }
        return "redirect:/admin/dashboard";
    }

    private static String unico(MultiValueMap<String, String> datos, String nombre) {
        var valores = datos.get(nombre);
        if (valores == null || valores.size() != 1 || valores.getFirst() == null) {
            throw new IllegalArgumentException();
        }
        return valores.getFirst();
    }
}
