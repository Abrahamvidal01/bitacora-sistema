package com.sistema.bitacora.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;

@Service
public class FolioReservaService {

    private final EntityManager entidad;
    private final Clock reloj;

    public FolioReservaService(
            EntityManager entidad,
            @Qualifier("relojReservas") Clock reloj) {
        this.entidad = entidad;
        this.reloj = reloj;
    }

    // Solo se utilizará desde el servicio de guardado definitivo.
    // Nunca desde la comprobación inicial ni al abrir el formulario.
    @Transactional(propagation = Propagation.MANDATORY)
    public String generarParaReserva() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "El folio requiere una transacción de escritura activa");
        }

        Object resultado;
        // ... el resto del código se queda exactamente igual ...
        try {
            resultado = entidad.createNativeQuery("""
                    SELECT ultimo_consecutivo
                    FROM contador_folios_reserva
                    WHERE id = 1
                    FOR UPDATE
                    """).getSingleResult();
        } catch (NoResultException ex) {
            throw new IllegalStateException(
                    "Falta la fila del contador de folios. Revisa la migración V6",
                    ex);
        }

        if (!(resultado instanceof Number numero)) {
            throw new IllegalStateException(
                    "El contador de folios contiene un valor inválido");
        }

        long anterior = numero.longValue();

        if (anterior < 0 || anterior == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "El contador de folios está fuera del rango permitido");
        }

        long siguiente = anterior + 1;

        int actualizadas = entidad.createNativeQuery("""
                UPDATE contador_folios_reserva
                SET ultimo_consecutivo = :siguiente
                WHERE id = 1
                  AND ultimo_consecutivo = :anterior
                """)
                .setParameter("siguiente", siguiente)
                .setParameter("anterior", anterior)
                .executeUpdate();

        if (actualizadas != 1) {
            throw new IllegalStateException(
                    "No se pudo actualizar el contador de folios");
        }

        int anio = LocalDate.now(reloj).getYear();

        return String.format(
                Locale.ROOT,
                "RES-%04d-%06d",
                anio,
                siguiente);
    }
}