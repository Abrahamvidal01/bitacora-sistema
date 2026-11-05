package com.sistema.bitacora.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EstadoBienInventarioTest {

    @Test
    void buenoPermiteReservar() {
        assertTrue(EstadoBienInventario.BUENO.esReservable());
    }

    @Test
    void regularPermiteReservar() {
        assertTrue(EstadoBienInventario.REGULAR.esReservable());
    }

    @Test
    void maloNoPermiteReservar() {
        assertFalse(EstadoBienInventario.MALO.esReservable());
    }

    @Test
    void normalizaEspaciosYMinusculas() {
        assertEquals(
                EstadoBienInventario.REGULAR,
                EstadoBienInventario.desdeTexto(" regular "));
    }

    @Test
    void rechazaEstadoDesconocido() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EstadoBienInventario.desdeTexto("OTRO"));
    }

    @Test
    void rechazaEstadoNuloOVacio() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EstadoBienInventario.desdeTexto(null));

        assertThrows(
                IllegalArgumentException.class,
                () -> EstadoBienInventario.desdeTexto(" "));
    }
}
