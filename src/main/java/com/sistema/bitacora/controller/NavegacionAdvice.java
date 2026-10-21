package com.sistema.bitacora.controller;

import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Set;

@ControllerAdvice(assignableTypes = ViewController.class)
public class NavegacionAdvice {

    @ModelAttribute
    public void prepararNavegacion(
            Authentication authentication,
            Model model) {

        Set<String> rolesDelModulo = Set.of(
                "ROLE_ADMINISTRADOR",
                "ROLE_SUPERVISOR",
                "ROLE_SOLICITANTE"
        );

        long cantidadRoles = authentication == null
                ? 0
                : authentication.getAuthorities().stream()
                .map(autoridad -> autoridad.getAuthority())
                .filter(rolesDelModulo::contains)
                .distinct()
                .count();

        boolean tieneVariosRoles = cantidadRoles > 1;

        model.addAttribute(
                "regresoUrl",
                tieneVariosRoles ? "/panel" : "/bitacoras"
        );

        model.addAttribute(
                "regresoTexto",
                tieneVariosRoles
                        ? "Volver a mis paneles"
                        : "Volver a Bitácoras"
        );
    }
}
