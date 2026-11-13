package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.Usuario;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class EspacioEliminacionService {
    private final EntityManager entidad;

    public EspacioEliminacionService(EntityManager entidad) {
        this.entidad = entidad;
    }

    // Llamar mediante el bean de Spring, no mediante new ni desde otra transaccion.
    // Los bloqueos se mantienen hasta finalizar esta transaccion.
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class, timeout = 15)
    public void eliminar(Long id) {
        if (id == null || id <= 0) {
            throw error(HttpStatus.BAD_REQUEST, "Identificador de espacio invalido");
        }
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !autenticacion.isAuthenticated()
                || autenticacion instanceof AnonymousAuthenticationToken
                || autenticacion.getName() == null || autenticacion.getName().isBlank()) {
            throw error(HttpStatus.FORBIDDEN, "Debes iniciar sesion como administrador");
        }

        // Mismo orden relativo que la confirmacion: espacio antes de usuario.
        Espacio espacio = entidad.find(Espacio.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (espacio == null) {
            throw error(HttpStatus.NOT_FOUND, "El espacio ya no existe. Actualiza la pagina");
        }
        List<Usuario> usuarios = entidad.createQuery("""
                select u from Usuario u where u.correoInstitucional = :correo
                """, Usuario.class)
                .setParameter("correo", autenticacion.getName())
                .setLockMode(LockModeType.PESSIMISTIC_READ)
                .getResultList();
        if (usuarios.size() != 1) {
            throw error(HttpStatus.FORBIDDEN, "La cuenta de administrador ya no es valida");
        }
        Usuario usuario = usuarios.getFirst();
        if (!Boolean.TRUE.equals(usuario.getActivo()) || usuario.getRoles() == null
                || usuario.getRoles().stream().noneMatch(rol -> rol != null
                && "ADMINISTRADOR".equals(rol.getNombreRol()))) {
            throw error(HttpStatus.FORBIDDEN, "La cuenta debe estar activa y ser administradora");
        }

        // No filtrar estados: una reserva cancelada tambien pertenece al historial.
        long reservas = contar("SELECT COUNT(*) FROM solicitudes_espacio WHERE espacio_id = :id", id);
        // Obligatorio: el FK del inventario tiene ON DELETE CASCADE.
        long inventario = contar("SELECT COUNT(*) FROM inventario_espacios WHERE espacio_id = :id", id);
        long recursos = contar("SELECT COUNT(*) FROM solicitud_recursos WHERE espacio_id = :id", id);
        if (reservas > 0 || inventario > 0 || recursos > 0) {
            throw error(HttpStatus.CONFLICT,
                    "No se puede eliminar: tiene reservas, inventario o recursos registrados. "
                    + "Usa Desactivar para conservar el historial. No se cambio su estado");
        }
        entidad.remove(espacio);
        entidad.flush();
        // El controlador solo informa exito despues de regresar del proxy transaccional.
    }

    private long contar(String sql, Long id) {
        Object resultado = entidad.createNativeQuery(sql).setParameter("id", id).getSingleResult();
        if (!(resultado instanceof Number numero)) {
            throw new IllegalStateException("No se pudo comprobar el historial del espacio");
        }
        long cantidad;
        try {
            cantidad = new BigDecimal(numero.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalStateException("Conteo de historial invalido", ex);
        }
        if (cantidad < 0) {
            throw new IllegalStateException("Conteo de historial invalido");
        }
        return cantidad;
    }

    private static ResponseStatusException error(HttpStatus estado, String mensaje) {
        return new ResponseStatusException(estado, mensaje);
    }
}
