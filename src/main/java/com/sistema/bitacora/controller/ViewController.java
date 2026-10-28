package com.sistema.bitacora.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.stream.Collectors;
import com.sistema.bitacora.service.EspacioService;

import com.sistema.bitacora.service.NombramientoDepartamentoService;

@Controller
public class ViewController {

    private final EspacioService espacioService;
    private final NombramientoDepartamentoService nombramientoService;

    public ViewController(
            EspacioService espacioService,
            NombramientoDepartamentoService nombramientoService) {

        this.espacioService = espacioService;
        this.nombramientoService = nombramientoService;
    }

    @GetMapping({"/", "/login"})
    public String index() {
        return "index";
    }

    @GetMapping("/bitacoras")
    public String bitacoras() {
        return "bitacoras";
    }

    @GetMapping("/panel")
    public String seleccionarPanel(
            Authentication authentication,
            Model model) {

        if (authentication == null) {
            return "redirect:/login";
        }

        Set<String> autoridades = authentication.getAuthorities()
                .stream()
                .map(autoridad -> autoridad.getAuthority())
                .collect(Collectors.toSet());

        boolean administrador =
                autoridades.contains("ROLE_ADMINISTRADOR");
        boolean supervisor =
                autoridades.contains("ROLE_SUPERVISOR");
        boolean solicitante =
                autoridades.contains("ROLE_SOLICITANTE");

        int cantidadRoles = (administrador ? 1 : 0)
                + (supervisor ? 1 : 0)
                + (solicitante ? 1 : 0);

        if (cantidadRoles == 0) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "No tienes un rol habilitado para este módulo"
            );
        }

        if (cantidadRoles == 1) {
            if (administrador) {
                return "redirect:/admin/dashboard";
            }

            if (supervisor) {
                return "redirect:/supervisor/dashboard";
            }

            return "redirect:/solicitante/dashboard";
        }

        model.addAttribute("administrador", administrador);
        model.addAttribute("supervisor", supervisor);
        model.addAttribute("solicitante", solicitante);

        return "seleccionar-panel";
    }

    @GetMapping("/admin/dashboard")
    public String adminDashboard(Model model) {
        model.addAttribute("espacios", espacioService.listar());
        return "admin/dashboard";
    }

    @GetMapping("/supervisor/dashboard")
    public String supervisorDashboard(Model model) {

        model.addAttribute(
                "titularesAbiertos",
                nombramientoService.listarAbiertosParaVista()
        );

        return "supervisor/dashboard";
    }

    @GetMapping("/solicitante/dashboard")
    public String solicitanteDashboard() {
        return "solicitante/dashboard";
    }
}