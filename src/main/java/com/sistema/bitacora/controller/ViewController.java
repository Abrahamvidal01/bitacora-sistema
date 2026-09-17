package com.sistema.bitacora.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ViewController {

    // Mapea tanto la raíz "/" como "/login" hacia tu archivo index.html
    @GetMapping({"/", "/login"})
    public String index() {
        return "index";
    }

    @GetMapping("/admin/dashboard")
    public String adminDashboard() {
        return "admin/dashboard";
    }

    @GetMapping("/supervisor/dashboard")
    public String supervisorDashboard() {
        return "supervisor/dashboard";
    }

    @GetMapping("/solicitante/dashboard")
    public String solicitanteDashboard() {
        return "solicitante/dashboard";
    }
}