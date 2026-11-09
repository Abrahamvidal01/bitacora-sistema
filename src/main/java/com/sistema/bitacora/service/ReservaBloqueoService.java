package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Departamento;
import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.repository.DepartamentoRepository;
import com.sistema.bitacora.repository.EspacioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReservaBloqueoService {

    private final DepartamentoRepository departamentoRepository;
    private final EspacioRepository espacioRepository;
    private final EntityManager entityManager;

    public ReservaBloqueoService(
            DepartamentoRepository departamentoRepository,
            EspacioRepository espacioRepository,
            EntityManager entityManager) {

        this.departamentoRepository = departamentoRepository;
        this.espacioRepository = espacioRepository;
        this.entityManager = entityManager;
    }

    /**
     * Solo para la futura confirmación.
     *
     * Debe llamarse antes de modificar las entidades.
     * No autoriza al usuario, no comprueba cupos ni guarda reservas.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BloqueoReserva bloquearParaConfirmar(
            Long departamentoId,
            Long espacioId) {

        if (departamentoId == null || departamentoId <= 0) {
            throw error("Indica un departamento válido");
        }

        if (espacioId == null || espacioId <= 0) {
            throw error("Selecciona un espacio válido");
        }

        comprobarTransaccion();

        // Orden obligatorio en este flujo: departamento primero.
        Departamento departamento = departamentoRepository
                .buscarPorIdParaActualizar(departamentoId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El departamento solicitante no existe"
                ));

        // Actualiza los datos si la entidad ya estaba cargada.
        // Debe realizarse antes de hacer cambios sobre ella.
        entityManager.refresh(
                departamento,
                LockModeType.PESSIMISTIC_WRITE
        );

        // Espacio después del departamento.
        Espacio espacio = espacioRepository
                .buscarPorIdParaReservar(espacioId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "El espacio seleccionado no existe"
                ));

        entityManager.refresh(
                espacio,
                LockModeType.PESSIMISTIC_WRITE
        );

        return new BloqueoReserva(departamento, espacio);
    }

    private void comprobarTransaccion() {
        if (!TransactionSynchronizationManager
                .isActualTransactionActive()
                || !TransactionSynchronizationManager
                .isSynchronizationActive()
                || TransactionSynchronizationManager
                .isCurrentTransactionReadOnly()) {

            throw new IllegalStateException(
                    "Los bloqueos de confirmación requieren "
                            + "una transacción activa de escritura"
            );
        }
        // Se elimina la verificación de isJoinedToTransaction()
        // porque en transacciones resource-local de Spring Boot no aplica.
    }

    private ResponseStatusException error(String mensaje) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                mensaje
        );
    }

    public record BloqueoReserva(
            Departamento departamento,
            Espacio espacio) {
    }
}