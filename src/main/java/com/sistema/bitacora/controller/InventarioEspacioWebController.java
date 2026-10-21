package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.EspacioService;
import com.sistema.bitacora.service.InventarioEspacioService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

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
}
