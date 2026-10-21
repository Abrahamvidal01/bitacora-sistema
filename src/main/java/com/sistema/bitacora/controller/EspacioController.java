package com.sistema.bitacora.controller;

import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.service.EspacioService;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/espacios")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class EspacioController {

    private final EspacioService espacioService;

    public EspacioController(EspacioService espacioService) {
        this.espacioService = espacioService;
    }

    public record DatosEspacio(
            String nombreEspacio,
            String ubicacion,
            Integer capacidad,
            String estadoActual
    ) {}

    public record CambioActivo(Boolean activo) {}

    @GetMapping
    public List<Espacio> listar() {
        return espacioService.listar();
    }

    @GetMapping("/{id}")
    public Espacio obtener(@PathVariable("id") Long id) {
        return espacioService.obtener(id);
    }

    @PostMapping
    public ResponseEntity<Espacio> crear(
            @RequestBody DatosEspacio datos) {

        Espacio creado = espacioService.crear(
                datos.nombreEspacio(),
                datos.ubicacion(),
                datos.capacidad(),
                datos.estadoActual()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(creado);
    }

    @PutMapping("/{id}")
    public Espacio editar(
            @PathVariable("id") Long id,
            @RequestBody DatosEspacio datos) {

        return espacioService.editar(
                id,
                datos.nombreEspacio(),
                datos.ubicacion(),
                datos.capacidad(),
                datos.estadoActual()
        );
    }

    @PatchMapping("/{id}/activo")
    public Espacio cambiarActivo(
            @PathVariable("id") Long id,
            @RequestBody CambioActivo datos) {

        return espacioService.cambiarActivo(id, datos.activo());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> manejarValidacion(
            ResponseStatusException error) {

        String mensaje = error.getReason() == null
                ? "No se pudo completar la operación"
                : error.getReason();

        return ResponseEntity.status(error.getStatusCode())
                .body(Map.of("mensaje", mensaje));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> manejarIntegridad(
            DataIntegrityViolationException error) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "mensaje",
                        "El cambio entra en conflicto con los datos existentes"
                ));
    }
}