package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.InventarioEspacio;
import com.sistema.bitacora.repository.EspacioRepository;
import com.sistema.bitacora.repository.InventarioEspacioRepository;
import com.sistema.bitacora.service.ReservaRecursosValidacionService.RecursoValidado;
import com.sistema.bitacora.service.ReservaRecursosValidacionService.SolicitudRecurso;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Pruebas unitarias: no inicia Spring ni conecta a MySQL.
// No prueba autorización web, transacciones ni concurrencia real.
class ReservaRecursosValidacionServiceTest {

    private static final Long ESPACIO_ID = 2L;

    private InventarioEspacioRepository inventario;
    private EspacioRepository espacios;
    private ReservaRecursosValidacionService servicio;

    private Espacio espacio;
    private InventarioEspacio buenas;
    private InventarioEspacio regulares;

    @BeforeEach
    void preparar() {
        inventario = mock(InventarioEspacioRepository.class);
        espacios = mock(EspacioRepository.class);

        servicio = new ReservaRecursosValidacionService(
                inventario, espacios);

        espacio = new Espacio();
        espacio.setId(ESPACIO_ID);
        espacio.setActivo(true);
        espacio.setEstadoActual("DISPONIBLE");

        when(espacios.findById(ESPACIO_ID))
                .thenReturn(Optional.of(espacio));

        buenas = articulo(1L, 30, "BUENO");
        regulares = articulo(6L, 15, "REGULAR");

        when(inventario.findByIdAndEspacio_Id(1L, ESPACIO_ID))
                .thenReturn(Optional.of(buenas));

        when(inventario.findByIdAndEspacio_Id(6L, ESPACIO_ID))
                .thenReturn(Optional.of(regulares));
    }

    @Test
    void aceptaBuenosYRegularesConSusCantidadesSeparadas() {
        var resultado = servicio.validar(
                ESPACIO_ID,
                List.of(
                        new SolicitudRecurso(1L, 30),
                        new SolicitudRecurso(6L, 15)
                )
        );

        assertEquals(
                List.of(
                        new RecursoValidado(
                                1L, "Sillas", "BUENO", 30, 30),
                        new RecursoValidado(
                                6L, "Sillas", "REGULAR", 15, 15)
                ),
                resultado
        );

        // La consulta no descuenta existencias.
        assertEquals(Integer.valueOf(30), buenas.getCantidad());
        assertEquals(Integer.valueOf(15), regulares.getCantidad());
    }

    @Test
    void aceptaNoSolicitarRecursosDeInventario() {
        assertEquals(
                List.of(),
                servicio.validar(ESPACIO_ID, List.of())
        );

        verifyNoInteractions(inventario);
    }

    @Test
    void rechazaArticuloMalo() {
        buenas.setEstadoBien("MALO");

        rechaza(409, List.of(new SolicitudRecurso(1L, 1)));
    }

    @Test
    void rechazaEstadoDesconocido() {
        buenas.setEstadoBien("ESTADO_NO_DEFINIDO");

        rechaza(409, List.of(new SolicitudRecurso(1L, 1)));
    }

    @Test
    void rechazaCantidadMayorALasExistenciasDelRegistro() {
        // Hay 45 entre ambos registros, pero el registro 1 solo tiene 30.
        rechaza(409, List.of(new SolicitudRecurso(1L, 31)));
    }

    @Test
    void rechazaArticuloQueNoExisteEnEseEspacio() {
        when(inventario.findByIdAndEspacio_Id(99L, ESPACIO_ID))
                .thenReturn(Optional.empty());

        rechaza(400, List.of(new SolicitudRecurso(99L, 1)));
    }

    @Test
    void rechazaRegistroRepetidoAunqueCadaCantidadSeaValida() {
        rechaza(
                400,
                List.of(
                        new SolicitudRecurso(1L, 20),
                        new SolicitudRecurso(1L, 20)
                )
        );

        verifyNoInteractions(inventario);
    }

    @Test
    void rechazaCantidadCero() {
        rechaza(400, List.of(new SolicitudRecurso(1L, 0)));
    }

    @Test
    void rechazaCantidadNegativa() {
        rechaza(400, List.of(new SolicitudRecurso(1L, -1)));
    }

    @Test
    void rechazaCantidadNula() {
        rechaza(400, List.of(new SolicitudRecurso(1L, null)));
    }

    @Test
    void rechazaEspacioDesactivado() {
        espacio.setActivo(false);

        rechaza(409, List.of(new SolicitudRecurso(1L, 1)));
    }

    @Test
    void rechazaEspacioInexistente() {
        when(espacios.findById(ESPACIO_ID))
                .thenReturn(Optional.empty());

        rechaza(404, List.of(new SolicitudRecurso(1L, 1)));
    }

    @Test
    void rechazaExistenciasNulas() {
        buenas.setCantidad(null);

        rechaza(409, List.of(new SolicitudRecurso(1L, 1)));
    }

    @Test
    void rechazaExistenciasNegativas() {
        buenas.setCantidad(-1);

        rechaza(409, List.of(new SolicitudRecurso(1L, 1)));
    }

    @Test
    void rechazaIdentificadorDeArticuloInvalido() {
        rechaza(400, List.of(new SolicitudRecurso(0L, 1)));

        verifyNoInteractions(inventario);
    }

    @Test
    void rechazaSeleccionNula() {
        rechaza(400, null);
    }

    private InventarioEspacio articulo(
            Long id,
            Integer cantidad,
            String estado) {

        InventarioEspacio articulo = new InventarioEspacio();
        articulo.setId(id);
        articulo.setNombreItem("Sillas");
        articulo.setCantidad(cantidad);
        articulo.setEstadoBien(estado);
        articulo.setEspacio(espacio);

        return articulo;
    }

    private void rechaza(
            int codigo,
            List<SolicitudRecurso> seleccion) {

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validar(ESPACIO_ID, seleccion)
        );

        assertEquals(codigo, error.getStatusCode().value());
    }
}
