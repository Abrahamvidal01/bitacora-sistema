package com.sistema.bitacora.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Pruebas unitarias: no inicia Spring ni conecta a MySQL.
// Las banderas transaccionales son simuladas, no transacciones reales.
class FolioReservaServiceTest {

    private EntityManager entidad;
    private Query lectura;
    private Query escritura;
    private FolioReservaService servicio;

    private boolean transaccionAnterior;
    private boolean soloLecturaAnterior;

    @BeforeEach
    void preparar() {
        transaccionAnterior =
                TransactionSynchronizationManager.isActualTransactionActive();
        soloLecturaAnterior =
                TransactionSynchronizationManager.isCurrentTransactionReadOnly();

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

        entidad = mock(EntityManager.class);
        lectura = mock(Query.class);
        escritura = mock(Query.class, RETURNS_SELF);

        when(entidad.isJoinedToTransaction()).thenReturn(true);

        when(entidad.createNativeQuery(
                contains("SELECT ultimo_consecutivo")))
                .thenReturn(lectura);

        when(entidad.createNativeQuery(
                contains("UPDATE contador_folios_reserva")))
                .thenReturn(escritura);

        when(lectura.getSingleResult()).thenReturn(0L);
        when(escritura.executeUpdate()).thenReturn(1);

        Clock reloj = Clock.fixed(
                Instant.parse("2026-11-04T18:00:00Z"),
                ZoneId.of("America/Mexico_City"));

        servicio = new FolioReservaService(entidad, reloj);
    }

    @AfterEach
    void restaurarBanderas() {
        TransactionSynchronizationManager
                .setActualTransactionActive(transaccionAnterior);
        TransactionSynchronizationManager
                .setCurrentTransactionReadOnly(soloLecturaAnterior);
    }

    @Test
    void generaPrimerFolioConSeisDigitos() {
        assertEquals(
                "RES-2026-000001",
                servicio.generarParaReserva());

        verify(escritura).setParameter("siguiente", 1L);
        verify(escritura).setParameter("anterior", 0L);
        verify(escritura).executeUpdate();

        verify(entidad).createNativeQuery(contains("FOR UPDATE"));
    }

    @Test
    void incrementaElConsecutivoExistente() {
        when(lectura.getSingleResult()).thenReturn(42L);

        assertEquals(
                "RES-2026-000043",
                servicio.generarParaReserva());

        verify(escritura).setParameter("siguiente", 43L);
        verify(escritura).setParameter("anterior", 42L);
    }

    @Test
    void noRecortaConsecutivosMayoresASeisDigitos() {
        when(lectura.getSingleResult()).thenReturn(999999L);

        assertEquals(
                "RES-2026-1000000",
                servicio.generarParaReserva());
    }

    @Test
    void cambiaElAnioSinReiniciarElConsecutivoGlobal() {
        when(lectura.getSingleResult()).thenReturn(42L);

        Clock reloj = Clock.fixed(
                Instant.parse("2027-01-01T18:00:00Z"),
                ZoneId.of("America/Mexico_City"));

        servicio = new FolioReservaService(entidad, reloj);

        assertEquals(
                "RES-2027-000043",
                servicio.generarParaReserva());
    }

    @Test
    void rechazaAusenciaDeTransaccion() {
        TransactionSynchronizationManager
                .setActualTransactionActive(false);

        assertThrows(
                IllegalStateException.class,
                servicio::generarParaReserva);

        verify(entidad, never()).createNativeQuery(anyString());
    }

    @Test
    void rechazaTransaccionDeSoloLectura() {
        TransactionSynchronizationManager
                .setCurrentTransactionReadOnly(true);

        assertThrows(
                IllegalStateException.class,
                servicio::generarParaReserva);

        verify(entidad, never()).createNativeQuery(anyString());
    }

    @Test
    void rechazaEntityManagerSinTransaccionAsociada() {
        when(entidad.isJoinedToTransaction()).thenReturn(false);

        assertThrows(
                IllegalStateException.class,
                servicio::generarParaReserva);

        verify(entidad, never()).createNativeQuery(anyString());
    }

    @Test
    void rechazaAusenciaDeLaFilaDelContador() {
        when(lectura.getSingleResult())
                .thenThrow(new NoResultException());

        assertThrows(
                IllegalStateException.class,
                servicio::generarParaReserva);

        verify(escritura, never()).executeUpdate();
    }

    @Test
    void rechazaContadorNoNumerico() {
        when(lectura.getSingleResult()).thenReturn("incorrecto");

        assertThrows(
                IllegalStateException.class,
                servicio::generarParaReserva);

        verify(escritura, never()).executeUpdate();
    }

    @Test
    void rechazaContadorNegativo() {
        when(lectura.getSingleResult()).thenReturn(-1L);

        assertThrows(
                IllegalStateException.class,
                servicio::generarParaReserva);

        verify(escritura, never()).executeUpdate();
    }

    @Test
    void rechazaDesbordamientoDelContador() {
        when(lectura.getSingleResult()).thenReturn(Long.MAX_VALUE);

        assertThrows(
                IllegalStateException.class,
                servicio::generarParaReserva);

        verify(escritura, never()).executeUpdate();
    }

    @Test
    void rechazaActualizacionQueNoModificoLaFila() {
        when(escritura.executeUpdate()).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                servicio::generarParaReserva);
    }
}
