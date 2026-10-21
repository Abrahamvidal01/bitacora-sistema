package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.EspacioService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class EspacioWebController {

    private final EspacioService espacioService;

    public EspacioWebController(EspacioService espacioService) {
        this.espacioService = espacioService;
    }

    @PostMapping("/admin/espacios")
    public String crear(
            @RequestParam("nombreEspacio") String nombreEspacio,
            @RequestParam("ubicacion") String ubicacion,
            @RequestParam("capacidad") Integer capacidad,
            @RequestParam("estadoActual") String estadoActual,
            RedirectAttributes mensajes) {

        try {
            espacioService.crear(
                    nombreEspacio,
                    ubicacion,
                    capacidad,
                    estadoActual
            );

            mensajes.addFlashAttribute(
                    "exitoEspacio",
                    "Espacio registrado correctamente."
            );

        } catch (ResponseStatusException error) {
            mensajes.addFlashAttribute(
                    "errorEspacio",
                    error.getReason() == null
                            ? "No se pudo guardar el espacio."
                            : error.getReason()
            );

        } catch (DataIntegrityViolationException error) {
            mensajes.addFlashAttribute(
                    "errorEspacio",
                    "No se pudo guardar: existe un conflicto con los datos."
            );
        }

        return "redirect:/admin/dashboard";
    }

    @PostMapping("/admin/espacios/editar")
    public String editar(
            @RequestParam("id") Long id,
            @RequestParam("nombreEspacio") String nombreEspacio,
            @RequestParam("ubicacion") String ubicacion,
            @RequestParam("capacidad") Integer capacidad,
            @RequestParam("estadoActual") String estadoActual,
            RedirectAttributes mensajes) {

        try {
            espacioService.editar(
                    id,
                    nombreEspacio,
                    ubicacion,
                    capacidad,
                    estadoActual
            );

            mensajes.addFlashAttribute(
                    "exitoEspacio",
                    "Espacio actualizado correctamente."
            );

        } catch (ResponseStatusException error) {
            mensajes.addFlashAttribute(
                    "errorEspacio",
                    error.getReason() == null
                            ? "No se pudo actualizar el espacio."
                            : error.getReason()
            );

        } catch (DataIntegrityViolationException error) {
            mensajes.addFlashAttribute(
                    "errorEspacio",
                    "El cambio entra en conflicto con los datos existentes."
            );
        }

        return "redirect:/admin/dashboard";
    }

    @PostMapping("/admin/espacios/activo")
    public String cambiarActivo(
            @RequestParam("id") Long id,
            @RequestParam("activo") Boolean activo,
            RedirectAttributes mensajes) {

        try {
            espacioService.cambiarActivo(id, activo);

            mensajes.addFlashAttribute(
                    "exitoEspacio",
                    Boolean.TRUE.equals(activo)
                            ? "Espacio activado correctamente."
                            : "Espacio desactivado correctamente."
            );

        } catch (ResponseStatusException error) {
            mensajes.addFlashAttribute(
                    "errorEspacio",
                    error.getReason() == null
                            ? "No se pudo cambiar la activación."
                            : error.getReason()
            );

        } catch (DataIntegrityViolationException error) {
            mensajes.addFlashAttribute(
                    "errorEspacio",
                    "El cambio entra en conflicto con los datos existentes."
            );
        }

        return "redirect:/admin/dashboard";
    }


}