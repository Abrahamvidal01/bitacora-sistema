package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.InventarioEspacio;
import com.sistema.bitacora.repository.EspacioRepository;
import com.sistema.bitacora.repository.InventarioEspacioRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
public class InventarioEspacioService {

    private final InventarioEspacioRepository inventarioRepository;
    private final EspacioRepository espacioRepository;

    public InventarioEspacioService(
            InventarioEspacioRepository inventarioRepository,
            EspacioRepository espacioRepository) {

        this.inventarioRepository = inventarioRepository;
        this.espacioRepository = espacioRepository;
    }

    @Transactional(readOnly = true)
    public List<InventarioEspacio> listar(Long espacioId) {
        buscarEspacio(espacioId);

        return inventarioRepository
                .findByEspacio_IdOrderByNombreItemAsc(espacioId);
    }

    @Transactional(readOnly = true)
    public InventarioEspacio obtener(Long espacioId, Long articuloId) {
        buscarEspacio(espacioId);
        return buscarArticulo(espacioId, articuloId);
    }

    @Transactional
    public InventarioEspacio crear(
            Long espacioId,
            String nombreItem,
            String descripcion,
            Integer cantidad,
            String estadoBien) {

        validar(nombreItem, cantidad, estadoBien);

        Espacio espacio = buscarEspacio(espacioId);

        InventarioEspacio articulo = new InventarioEspacio();
        articulo.setEspacio(espacio);

        asignarDatos(
                articulo, nombreItem, descripcion, cantidad, estadoBien
        );

        return inventarioRepository.saveAndFlush(articulo);
    }

    @Transactional
    public InventarioEspacio editar(
            Long espacioId,
            Long articuloId,
            String nombreItem,
            String descripcion,
            Integer cantidad,
            String estadoBien) {

        validar(nombreItem, cantidad, estadoBien);
        buscarEspacio(espacioId);

        InventarioEspacio articulo =
                buscarArticulo(espacioId, articuloId);

        asignarDatos(
                articulo, nombreItem, descripcion, cantidad, estadoBien
        );

        return inventarioRepository.saveAndFlush(articulo);
    }

    private Espacio buscarEspacio(Long espacioId) {
        if (espacioId == null || espacioId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Identificador de espacio inválido"
            );
        }

        return espacioRepository.findById(espacioId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El espacio no existe"
                ));
    }

    private InventarioEspacio buscarArticulo(
            Long espacioId,
            Long articuloId) {

        if (articuloId == null || articuloId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Identificador de artículo inválido"
            );
        }

        return inventarioRepository
                .findByIdAndEspacio_Id(articuloId, espacioId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El artículo no existe en este espacio"
                ));
    }

    private void validar(
            String nombreItem,
            Integer cantidad,
            String estadoBien) {

        if (nombreItem == null || nombreItem.isBlank()
                || nombreItem.trim().length() > 150) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El nombre es obligatorio y admite hasta 150 caracteres"
            );
        }

        if (cantidad == null || cantidad <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La cantidad debe ser mayor que cero"
            );
        }

        if (estadoBien == null || estadoBien.isBlank()
                || estadoBien.trim().length() > 50) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El estado es obligatorio y admite hasta 50 caracteres"
            );
        }
    }

    private void asignarDatos(
            InventarioEspacio articulo,
            String nombreItem,
            String descripcion,
            Integer cantidad,
            String estadoBien) {

        articulo.setNombreItem(nombreItem.trim());

        articulo.setDescripcion(
                descripcion == null || descripcion.isBlank()
                        ? null
                        : descripcion.trim()
        );

        articulo.setCantidad(cantidad);
        articulo.setEstadoBien(
                estadoBien.trim().toUpperCase(Locale.ROOT)
        );
    }
}
