package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Departamento;
import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.repository.DepartamentoRepository;
import com.sistema.bitacora.repository.EspacioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Pruebas unitarias con repositorios y EntityManager simulados.
// No abren una transacción real ni comprueban concurrencia en MariaDB.
class ReservaBloqueoServiceTest {

    private static final Long DEPARTAMENTO_ID = 1L;
    private static final Long ESPACIO_ID = 2L;

    private DepartamentoRepository departamentos;
    private EspacioRepository espacios;
    private EntityManager entityManager;

    private Departamento departamento;
    private Espacio espacio;

    private ReservaBloqueoService servicio;

    @BeforeEach
    void preparar() {
        departamentos = mock(DepartamentoRepository.class);
        espacios = mock(EspacioRepository.class);
        entityManager = mock(EntityManager.class);

        departamento = new Departamento();
        departamento.setId(DEPARTAMENTO_ID);

        espacio = new Espacio();
        espacio.setId(ESPACIO_ID);

        when(departamentos.buscarPorIdParaActualizar(DEPARTAMENTO_ID))
                .thenReturn(Optional.of(departamento));

        when(espacios.buscarPorIdParaReservar(ESPACIO_ID))
                .thenReturn(Optional.of(espacio));

        when(entityManager.isJoinedToTransaction()).thenReturn(true);

        servicio = new ReservaBloqueoService(
                departamentos,
                espacios,
                entityManager
        );

        // Solo simula el contexto necesario para probar las comprobaciones.
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager
                .setActualTransactionActive(true);
        TransactionSynchronizationManager
                .setCurrentTransactionReadOnly(false);
    }

    @AfterEach
    void limpiarContextoSimulado() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager
                .setActualTransactionActive(false);

        TransactionSynchronizationManager
                .setCurrentTransactionReadOnly(false);
    }

    @Test
    void bloqueaYRefrescaPrimeroDepartamentoYDespuesEspacio() {
        var resultado = bloquear();

        assertSame(departamento, resultado.departamento());
        assertSame(espacio, resultado.espacio());

        var orden = inOrder(
                departamentos,
                espacios,
                entityManager
        );

        orden.verify(departamentos)
                .buscarPorIdParaActualizar(DEPARTAMENTO_ID);

        orden.verify(entityManager).refresh(
                departamento,
                LockModeType.PESSIMISTIC_WRITE
        );

        orden.verify(espacios)
                .buscarPorIdParaReservar(ESPACIO_ID);

        orden.verify(entityManager).refresh(
                espacio,
                LockModeType.PESSIMISTIC_WRITE
        );
    }

    @Test
    void rechazaSiNoHayTransaccionActiva() {
        TransactionSynchronizationManager
                .setActualTransactionActive(false);

        assertThrows(IllegalStateException.class, this::bloquear);

        verifyNoInteractions(departamentos, espacios);
    }

    @Test
    void rechazaUnaTransaccionDeSoloLectura() {
        TransactionSynchronizationManager
                .setCurrentTransactionReadOnly(true);

        assertThrows(IllegalStateException.class, this::bloquear);

        verifyNoInteractions(departamentos, espacios);
    }

    @Test
    void rechazaSiNoHaySincronizacionActiva() {
        TransactionSynchronizationManager.clearSynchronization();

        assertThrows(IllegalStateException.class, this::bloquear);

        verifyNoInteractions(departamentos, espacios);
    }

    @Test
    void rechazaEntityManagerFueraDeLaTransaccion() {
        when(entityManager.isJoinedToTransaction()).thenReturn(false);

        assertThrows(IllegalStateException.class, this::bloquear);

        verifyNoInteractions(departamentos, espacios);
    }

    @Test
    void rechazaDepartamentoNulo() {
        comprobarIdInvalido(null, ESPACIO_ID);
    }

    @Test
    void rechazaDepartamentoCero() {
        comprobarIdInvalido(0L, ESPACIO_ID);
    }

    @Test
    void rechazaEspacioNulo() {
        comprobarIdInvalido(DEPARTAMENTO_ID, null);
    }

    @Test
    void rechazaEspacioCero() {
        comprobarIdInvalido(DEPARTAMENTO_ID, 0L);
    }

    @Test
    void noIntentaBloquearEspacioSiFaltaElDepartamento() {
        when(departamentos.buscarPorIdParaActualizar(DEPARTAMENTO_ID))
                .thenReturn(Optional.empty());

        var error = assertThrows(
                ResponseStatusException.class,
                this::bloquear
        );

        assertEquals(404, error.getStatusCode().value());
        verifyNoInteractions(espacios);

        verify(entityManager, never()).refresh(
                departamento,
                LockModeType.PESSIMISTIC_WRITE
        );
    }

    @Test
    void informaCuandoElEspacioNoExiste() {
        when(espacios.buscarPorIdParaReservar(ESPACIO_ID))
                .thenReturn(Optional.empty());

        var error = assertThrows(
                ResponseStatusException.class,
                this::bloquear
        );

        assertEquals(404, error.getStatusCode().value());

        verify(entityManager).refresh(
                departamento,
                LockModeType.PESSIMISTIC_WRITE
        );

        verify(entityManager, never()).refresh(
                espacio,
                LockModeType.PESSIMISTIC_WRITE
        );
    }

    @Test
    void noContinuaSiFallaElRefreshDelDepartamento() {
        var fallo = new IllegalStateException(
                "Fallo simulado al refrescar departamento"
        );

        doThrow(fallo).when(entityManager).refresh(
                departamento,
                LockModeType.PESSIMISTIC_WRITE
        );

        var error = assertThrows(
                IllegalStateException.class,
                this::bloquear
        );

        assertSame(fallo, error);
        verifyNoInteractions(espacios);
    }

    @Test
    void propagaElFalloDeBloqueoDelEspacio() {
        var fallo = new CannotAcquireLockException(
                "Fallo de bloqueo simulado"
        );

        when(espacios.buscarPorIdParaReservar(ESPACIO_ID))
                .thenThrow(fallo);

        var error = assertThrows(
                CannotAcquireLockException.class,
                this::bloquear
        );

        assertSame(fallo, error);

        verify(entityManager, never()).refresh(
                espacio,
                LockModeType.PESSIMISTIC_WRITE
        );
    }

    private ReservaBloqueoService.BloqueoReserva bloquear() {
        return servicio.bloquearParaConfirmar(
                DEPARTAMENTO_ID,
                ESPACIO_ID
        );
    }

    private void comprobarIdInvalido(
            Long departamentoId,
            Long espacioId) {

        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.bloquearParaConfirmar(
                        departamentoId,
                        espacioId
                )
        );

        assertEquals(400, error.getStatusCode().value());

        verifyNoInteractions(
                departamentos,
                espacios,
                entityManager
        );
    }
}
