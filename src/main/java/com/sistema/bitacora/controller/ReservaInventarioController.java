package com.sistema.bitacora.controller;

import com.sistema.bitacora.service.InventarioEspacioService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class ReservaInventarioController {

    private final InventarioEspacioService inventarioService;

    public ReservaInventarioController(
            InventarioEspacioService inventarioService) {
        this.inventarioService = inventarioService;
    }

    @GetMapping(
            value = "/admin/espacios/{espacioId}/inventario/datos",
            produces = "application/json"
    )
    @ResponseBody
    public List<ArticuloRespuesta> listar(
            @PathVariable("espacioId") Long espacioId) {

        return inventarioService.listar(espacioId)
                .stream()
                .map(articulo -> new ArticuloRespuesta(
                        articulo.getId(),
                        articulo.getNombreItem(),
                        articulo.getCantidad(),
                        articulo.getEstadoBien()
                ))
                .toList();
    }

    public record ArticuloRespuesta(
            Long id,
            String nombreItem,
            Integer cantidad,
            String estadoBien
    ) {
    }
}
