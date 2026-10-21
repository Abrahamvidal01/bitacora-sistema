package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.repository.EspacioRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class EspacioService {

    private final EspacioRepository espacioRepository;

    public EspacioService(EspacioRepository espacioRepository) {
        this.espacioRepository = espacioRepository;
    }

    @Transactional(readOnly = true)
    public List<Espacio> listar() {
        return espacioRepository.findAllByOrderByNombreEspacioAsc();
    }

    @Transactional(readOnly = true)
    public Espacio obtener(Long id) {
        return buscar(id);
    }

    @Transactional
    public Espacio crear(
            String nombre,
            String ubicacion,
            Integer capacidad,
            String estado) {

        validar(nombre, ubicacion, capacidad, estado);

        String nombreLimpio = nombre.trim();

        if (espacioRepository
                .findByNombreEspacioIgnoreCase(nombreLimpio)
                .isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ya existe un espacio con ese nombre"
            );
        }

        Espacio espacio = new Espacio();

        asignarDatos(espacio, nombreLimpio, ubicacion, capacidad, estado);
        espacio.setActivo(true);

        return espacioRepository.saveAndFlush(espacio);
    }

    @Transactional
    public Espacio editar(
            Long id,
            String nombre,
            String ubicacion,
            Integer capacidad,
            String estado) {

        validar(nombre, ubicacion, capacidad, estado);

        Espacio espacio = buscar(id);
        String nombreLimpio = nombre.trim();

        if (espacioRepository
                .existsByNombreEspacioIgnoreCaseAndIdNot(
                        nombreLimpio, id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Otro espacio ya tiene ese nombre"
            );
        }

        asignarDatos(espacio, nombreLimpio, ubicacion, capacidad, estado);

        return espacioRepository.saveAndFlush(espacio);
    }

    @Transactional
    public Espacio cambiarActivo(Long id, Boolean activo) {
        if (activo == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debes indicar si el espacio estará activo"
            );
        }

        Espacio espacio = buscar(id);
        espacio.setActivo(activo);

        return espacioRepository.saveAndFlush(espacio);
    }

    private Espacio buscar(Long id) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Identificador de espacio inválido"
            );
        }

        return espacioRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El espacio no existe"
                ));
    }

    private void validar(
            String nombre,
            String ubicacion,
            Integer capacidad,
            String estado) {

        if (nombre == null || nombre.isBlank()
                || nombre.trim().length() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El nombre es obligatorio y admite hasta 100 caracteres"
            );
        }

        if (ubicacion == null || ubicacion.isBlank()
                || ubicacion.trim().length() > 150) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La ubicación es obligatoria y admite hasta 150 caracteres"
            );
        }

        if (capacidad == null || capacidad <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La capacidad debe ser mayor que cero"
            );
        }

        if (estado == null || !Set.of(
                "DISPONIBLE", "MANTENIMIENTO"
        ).contains(estado.trim().toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El estado debe ser DISPONIBLE o MANTENIMIENTO"
            );
        }
    }

    private void asignarDatos(
            Espacio espacio,
            String nombre,
            String ubicacion,
            Integer capacidad,
            String estado) {

        espacio.setNombreEspacio(nombre.trim());
        espacio.setUbicacion(ubicacion.trim());
        espacio.setCapacidad(capacidad);
        espacio.setEstadoActual(
                estado.trim().toUpperCase(Locale.ROOT)
        );
    }
}
