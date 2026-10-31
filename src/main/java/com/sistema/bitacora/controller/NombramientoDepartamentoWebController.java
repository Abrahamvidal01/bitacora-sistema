package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.NombramientoDepartamentoService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

@Controller
@RequestMapping("/supervisor/nombramientos")
@PreAuthorize("hasRole('SUPERVISOR')")
public class NombramientoDepartamentoWebController {

    private final NombramientoDepartamentoService nombramientoService;

    public NombramientoDepartamentoWebController(
            NombramientoDepartamentoService nombramientoService) {
        this.nombramientoService = nombramientoService;
    }

    @GetMapping("/nuevo")
    public String nuevo(Model model) {
        model.addAttribute("opciones",
                nombramientoService.opcionesParaFormulario());

        if (!model.containsAttribute("departamentoSeleccionado")) {
            model.addAttribute("departamentoSeleccionado", "");
        }
        if (!model.containsAttribute("usuarioSeleccionado")) {
            model.addAttribute("usuarioSeleccionado", "");
        }
        if (!model.containsAttribute("fechaInicioForm")) {
            model.addAttribute("fechaInicioForm", "");
        }

        return "supervisor/nombramiento-form";
    }

    @PostMapping("/registrar")
    public String registrar(
            @RequestParam(name = "departamentoId", defaultValue = "")
            String departamentoId,
            @RequestParam(name = "usuarioId", defaultValue = "")
            String usuarioId,
            @RequestParam(name = "fechaInicio", defaultValue = "")
            String fechaInicio,
            RedirectAttributes redirect) {

        try {
            Long idDepartamento = Long.valueOf(departamentoId.trim());
            Long idUsuario = Long.valueOf(usuarioId.trim());
            LocalDateTime inicio = LocalDateTime.parse(fechaInicio.trim());

            nombramientoService.registrar(idDepartamento, idUsuario, inicio);

            redirect.addFlashAttribute("exito",
                    "Nombramiento registrado correctamente. "
                            + "Puedes consultarlo en el panel del supervisor.");
        } catch (NumberFormatException | DateTimeParseException ex) {
            prepararError(redirect, departamentoId, usuarioId, fechaInicio,
                    "Selecciona un departamento, un usuario y una fecha de inicio válida.");
        } catch (ResponseStatusException ex) {
            int estado = ex.getStatusCode().value();
            if (estado != 400 && estado != 404 && estado != 409) {
                throw ex;
            }
            prepararError(redirect, departamentoId, usuarioId, fechaInicio,
                    ex.getReason() == null
                            ? "No se pudo registrar el nombramiento."
                            : ex.getReason());
        }

        return "redirect:/supervisor/nombramientos/nuevo";
    }

    @GetMapping("/{nombramientoId}/finalizar")
    public String formularioFinalizar(
            @PathVariable("nombramientoId") Long nombramientoId,
            Model model,
            RedirectAttributes redirect) {

        try {
            model.addAttribute("nombramiento",
                    nombramientoService.obtenerAbiertoParaFinalizar(nombramientoId));
        } catch (ResponseStatusException ex) {
            int estado = ex.getStatusCode().value();
            if (estado != 400 && estado != 404 && estado != 409) {
                throw ex;
            }
            redirect.addFlashAttribute("errorNombramiento", ex.getReason());
            return "redirect:/supervisor/dashboard";
        }

        if (!model.containsAttribute("fechaFinForm")) {
            model.addAttribute("fechaFinForm", "");
        }
        if (!model.containsAttribute("motivoFinForm")) {
            model.addAttribute("motivoFinForm", "");
        }

        return "supervisor/nombramiento-finalizar";
    }

    @PostMapping("/{nombramientoId}/finalizar")
    public String finalizar(
            @PathVariable("nombramientoId") Long nombramientoId,
            @RequestParam(name = "fechaFin", defaultValue = "") String fechaFin,
            @RequestParam(name = "motivoFin", defaultValue = "") String motivoFin,
            RedirectAttributes redirect) {

        try {
            LocalDateTime fin = LocalDateTime.parse(fechaFin.trim());
            nombramientoService.finalizar(nombramientoId, fin, motivoFin);

            redirect.addFlashAttribute("exitoNombramiento",
                    "Nombramiento finalizado correctamente. "
                            + "Se conservó el registro histórico y no se eliminó la cuenta.");
            return "redirect:/supervisor/dashboard";
        } catch (DateTimeParseException ex) {
            prepararErrorFinalizacion(redirect, fechaFin, motivoFin,
                    "Indica una fecha y hora de finalización válida.");
        } catch (ResponseStatusException ex) {
            int estado = ex.getStatusCode().value();
            if (estado == 404 || estado == 409) {
                redirect.addFlashAttribute("errorNombramiento", ex.getReason());
                return "redirect:/supervisor/dashboard";
            }
            if (estado != 400) {
                throw ex;
            }
            prepararErrorFinalizacion(redirect, fechaFin, motivoFin,
                    ex.getReason() == null
                            ? "No se pudo finalizar el nombramiento."
                            : ex.getReason());
        }

        return "redirect:/supervisor/nombramientos/"
                + nombramientoId + "/finalizar";
    }

    private void prepararError(
            RedirectAttributes redirect,
            String departamentoId,
            String usuarioId,
            String fechaInicio,
            String mensaje) {

        redirect.addFlashAttribute("error", mensaje);
        redirect.addFlashAttribute("departamentoSeleccionado", departamentoId);
        redirect.addFlashAttribute("usuarioSeleccionado", usuarioId);
        redirect.addFlashAttribute("fechaInicioForm", fechaInicio);
    }

    private void prepararErrorFinalizacion(
            RedirectAttributes redirect,
            String fechaFin,
            String motivoFin,
            String mensaje) {

        redirect.addFlashAttribute("error", mensaje);
        redirect.addFlashAttribute("fechaFinForm", fechaFin);
        redirect.addFlashAttribute("motivoFinForm", motivoFin);
    }
}
