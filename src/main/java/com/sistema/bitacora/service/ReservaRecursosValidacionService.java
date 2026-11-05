package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.EstadoBienInventario;
import com.sistema.bitacora.entity.InventarioEspacio;
import com.sistema.bitacora.repository.EspacioRepository;
import com.sistema.bitacora.repository.InventarioEspacioRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ReservaRecursosValidacionService {

    private final InventarioEspacioRepository inventarioRepository;
    private final EspacioRepository espacioRepository;

    public ReservaRecursosValidacionService(
            InventarioEspacioRepository inventarioRepository,
            EspacioRepository espacioRepository) {
        this.inventarioRepository = inventarioRepository;
        this.espacioRepository = espacioRepository;
    }

    @Transactional(readOnly = true)
    public List<RecursoValidado> validar(
            Long espacioId,
            List<SolicitudRecurso> seleccion) {

        if (espacioId == null || espacioId <= 0) {
            throw error(HttpStatus.BAD_REQUEST,
                    "Selecciona un espacio válido");
        }

        if (seleccion == null) {
            throw error(HttpStatus.BAD_REQUEST,
                    "La selección de recursos no es válida");
        }

        Set<Long> ids = new HashSet<>();

        for (SolicitudRecurso recurso : seleccion) {
            if (recurso == null
                    || recurso.articuloId() == null
                    || recurso.articuloId() <= 0
                    || recurso.cantidad() == null
                    || recurso.cantidad() <= 0) {
                throw error(HttpStatus.BAD_REQUEST,
                        "Cada recurso debe tener un identificador válido "
                                + "y una cantidad entera mayor que cero");
            }

            if (!ids.add(recurso.articuloId())) {
                throw error(HttpStatus.BAD_REQUEST,
                        "El mismo registro de inventario no puede repetirse");
            }
        }

        Espacio espacio = espacioRepository.findById(espacioId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND,
                        "El espacio no existe"));

        if (!Boolean.TRUE.equals(espacio.getActivo())) {
            throw error(HttpStatus.CONFLICT,
                    "El espacio está desactivado");
        }

        List<RecursoValidado> resultado = new ArrayList<>();

        for (SolicitudRecurso recurso : seleccion) {
            InventarioEspacio articulo = inventarioRepository
                    .findByIdAndEspacio_Id(recurso.articuloId(), espacioId)
                    .orElseThrow(() -> error(HttpStatus.BAD_REQUEST,
                            "Un artículo seleccionado no existe "
                                    + "en el espacio indicado"));

            EstadoBienInventario estado;

            try {
                estado = EstadoBienInventario.desdeTexto(
                        articulo.getEstadoBien());
            } catch (IllegalArgumentException ex) {
                throw error(HttpStatus.CONFLICT,
                        "Un artículo tiene un estado desconocido. "
                                + "Revisa su inventario");
            }

            if (!estado.esReservable()) {
                throw error(HttpStatus.CONFLICT,
                        "El artículo " + articulo.getNombreItem()
                                + " está en estado MALO y no puede reservarse");
            }

            Integer existencias = articulo.getCantidad();

            if (existencias == null || existencias < 0) {
                throw error(HttpStatus.CONFLICT,
                        "Un artículo tiene existencias inconsistentes");
            }

            if (recurso.cantidad() > existencias) {
                throw error(HttpStatus.CONFLICT,
                        "Cantidad insuficiente para "
                                + articulo.getNombreItem()
                                + " (" + estado.name() + "). "
                                + "Existencias consultadas: " + existencias);
            }

            resultado.add(new RecursoValidado(
                    articulo.getId(),
                    articulo.getNombreItem(),
                    estado.name(),
                    recurso.cantidad(),
                    existencias
            ));
        }

        return List.copyOf(resultado);
    }

    private ResponseStatusException error(
            HttpStatus estado,
            String mensaje) {
        return new ResponseStatusException(estado, mensaje);
    }

    public record SolicitudRecurso(
            Long articuloId,
            Integer cantidad) {
    }

    public record RecursoValidado(
            Long articuloId,
            String nombreItem,
            String estadoBien,
            Integer cantidadSolicitada,
            Integer existenciasConsultadas) {
    }
}
