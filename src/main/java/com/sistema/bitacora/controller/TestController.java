package com.sistema.bitacora.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/test")

public class TestController {

    // Endpoint accesible para cualquier usuario autenticado (con Token válido)
    @GetMapping("/publico-autenticado")
    public ResponseEntity<?> testAutenticado() {
        return ResponseEntity.ok(Map.of("mensaje", " Acceso concedido: Usuario autenticado con token válido."));
    }

    // Solo accesible si el usuario tiene el rol ADMINISTRADOR
    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<?> testAdmin() {
        return ResponseEntity.ok(Map.of("mensaje", " Acceso concedido: Tienes el rol de ADMINISTRADOR."));
    }

    // Accessible por SUPERVISOR o ADMINISTRADOR
    @GetMapping("/supervisor")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'SUPERVISOR')")
    public ResponseEntity<?> testSupervisor() {
        return ResponseEntity.ok(Map.of("mensaje", " Acceso concedido: Tienes el rol de SUPERVISOR o ADMIN."));
    }

    // Solo accesible para usuarios con rol SOLICITANTE
    @GetMapping("/solicitante")
    @PreAuthorize("hasRole('SOLICITANTE')")
    public ResponseEntity<?> testSolicitante() {
        return ResponseEntity.ok(Map.of("mensaje", " Acceso concedido: Tienes el rol de SOLICITANTE."));
    }

}
