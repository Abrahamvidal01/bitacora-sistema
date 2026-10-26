package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.EspacioService;
import com.sistema.bitacora.service.InventarioEspacioService;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class InventarioEspacioWebController {

    private final EspacioService espacioService;
    private final InventarioEspacioService inventarioService;

    public InventarioEspacioWebController(
            EspacioService espacioService,
            InventarioEspacioService inventarioService) {

        this.espacioService = espacioService;
        this.inventarioService = inventarioService;
    }

    @GetMapping("/admin/espacios/{espacioId}/inventario")
    public String listar(
            @PathVariable("espacioId") Long espacioId,
            Model model) {

        model.addAttribute(
                "espacio",
                espacioService.obtener(espacioId)
        );

        model.addAttribute(
                "articulos",
                inventarioService.listar(espacioId)
        );

        return "admin/inventario";
    }

    @PostMapping("/admin/espacios/{espacioId}/inventario")
    public String crear(
            @PathVariable("espacioId") Long espacioId,
            @RequestParam(name = "nombreItem", defaultValue = "")
            String nombreItem,
            @RequestParam(name = "descripcion", defaultValue = "")
            String descripcion,
            @RequestParam(name = "cantidad", defaultValue = "")
            String cantidad,
            @RequestParam(name = "estadoBien", defaultValue = "")
            String estadoBien,
            Model model,
            RedirectAttributes redirectAttributes) {

        // Conserva los valores si hay un error de validación.
        model.addAttribute("nombreForm", nombreItem);
        model.addAttribute("descripcionForm", descripcion);
        model.addAttribute("cantidadForm", cantidad);
        model.addAttribute("estadoForm", estadoBien);

        Integer cantidadNumero;

        try {
            cantidadNumero = Integer.valueOf(cantidad.trim());
        } catch (NumberFormatException ex) {
            model.addAttribute(
                    "error",
                    "La cantidad debe ser un número entero válido."
            );
            return listar(espacioId, model);
        }

        try {
            inventarioService.crear(
                    espacioId,
                    nombreItem,
                    descripcion,
                    cantidadNumero,
                    estadoBien
            );
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value()
                    != HttpStatus.BAD_REQUEST.value()) {
                throw ex;
            }

            model.addAttribute("error", ex.getReason());
            return listar(espacioId, model);
        }

        redirectAttributes.addFlashAttribute(
                "exito",
                "Artículo registrado correctamente."
        );

        return "redirect:/admin/espacios/"
                + espacioId + "/inventario";
    }

    @GetMapping("/admin/espacios/{espacioId}/inventario/{articuloId}/editar")
    public String mostrarEdicion(
            @PathVariable("espacioId") Long espacioId,
            @PathVariable("articuloId") Long articuloId,
            Model model) {

        var articulo = inventarioService.obtener(espacioId, articuloId);

        model.addAttribute("edicionId", articulo.getId());
        model.addAttribute("nombreForm", articulo.getNombreItem());
        model.addAttribute("descripcionForm", articulo.getDescripcion());
        model.addAttribute("cantidadForm", articulo.getCantidad());
        model.addAttribute("estadoForm", articulo.getEstadoBien());

        return listar(espacioId, model);
    }

    @PostMapping("/admin/espacios/{espacioId}/inventario/{articuloId}/editar")
    public String guardarEdicion(
            @PathVariable("espacioId") Long espacioId,
            @PathVariable("articuloId") Long articuloId,
            @RequestParam(name = "nombreItem", defaultValue = "")
            String nombreItem,
            @RequestParam(name = "descripcion", defaultValue = "")
            String descripcion,
            @RequestParam(name = "cantidad", defaultValue = "")
            String cantidad,
            @RequestParam(name = "estadoBien", defaultValue = "")
            String estadoBien,
            Model model,
            RedirectAttributes redirectAttributes) {

        // Verifica que el artículo pertenezca al espacio.
        inventarioService.obtener(espacioId, articuloId);

        // Conserva la edición y los valores si hay un error.
        model.addAttribute("edicionId", articuloId);
        model.addAttribute("nombreForm", nombreItem);
        model.addAttribute("descripcionForm", descripcion);
        model.addAttribute("cantidadForm", cantidad);
        model.addAttribute("estadoForm", estadoBien);

        Integer cantidadNumero;

        try {
            cantidadNumero = Integer.valueOf(cantidad.trim());
        } catch (NumberFormatException ex) {
            model.addAttribute(
                    "error",
                    "La cantidad debe ser un número entero válido."
            );
            return listar(espacioId, model);
        }

        try {
            inventarioService.editar(
                    espacioId,
                    articuloId,
                    nombreItem,
                    descripcion,
                    cantidadNumero,
                    estadoBien
            );
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value()
                    != HttpStatus.BAD_REQUEST.value()) {
                throw ex;
            }

            model.addAttribute("error", ex.getReason());
            return listar(espacioId, model);
        }

        redirectAttributes.addFlashAttribute(
                "exito",
                "Artículo actualizado correctamente."
        );

        return "redirect:/admin/espacios/"
                + espacioId + "/inventario";
    }

}
