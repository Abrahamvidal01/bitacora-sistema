package com.sistema.bitacora.entity;

import java.util.Locale;

public enum EstadoBienInventario {

    BUENO(true),
    REGULAR(true),
    MALO(false);

    private final boolean reservable;

    EstadoBienInventario(boolean reservable) {
        this.reservable = reservable;
    }

    public boolean esReservable() {
        return reservable;
    }

    public static EstadoBienInventario desdeTexto(String valor) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(
                    "El estado del artículo es obligatorio");
        }

        return EstadoBienInventario.valueOf(
                valor.trim().toUpperCase(Locale.ROOT));
    }
}
