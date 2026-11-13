package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.InventarioEspacio;
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
public class InventarioEliminacionService {
    private final EntityManager entidad;
    public InventarioEliminacionService(EntityManager entidad) { this.entidad = entidad; }

    // Invocar mediante el bean Spring desde el controlador, no desde otra transaccion.
    // Elimina la fila completa del articulo, NO resta una unidad de su cantidad.
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class, timeout = 15)
    public void eliminar(Long espacioId, Long articuloId) {
        if (espacioId == null || espacioId <= 0 || articuloId == null || articuloId <= 0) {
            throw error(HttpStatus.BAD_REQUEST, "Identificador de espacio o articulo invalido");
        }
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !autenticacion.isAuthenticated()
                || autenticacion instanceof AnonymousAuthenticationToken
                || autenticacion.getName() == null || autenticacion.getName().isBlank()) {
            throw error(HttpStatus.FORBIDDEN, "Debes iniciar sesion como administrador");
        }
        // Orden compatible con confirmacion: espacio -> usuario -> inventario.
        Espacio espacio = entidad.find(Espacio.class, espacioId, LockModeType.PESSIMISTIC_WRITE);
        if (espacio == null) {
            throw error(HttpStatus.NOT_FOUND, "El espacio no existe. Actualiza la pagina");
        }
        List<Usuario> usuarios = entidad.createQuery("""
                select u from Usuario u where u.correoInstitucional = :correo
                """, Usuario.class).setParameter("correo", autenticacion.getName())
                .setLockMode(LockModeType.PESSIMISTIC_READ).getResultList();
        if (usuarios.size() != 1) {
            throw error(HttpStatus.FORBIDDEN, "La cuenta de administrador ya no es valida");
        }
        Usuario usuario = usuarios.getFirst();
        if (!Boolean.TRUE.equals(usuario.getActivo()) || usuario.getRoles() == null
                || usuario.getRoles().stream().noneMatch(rol -> rol != null
                && "ADMINISTRADOR".equals(rol.getNombreRol()))) {
            throw error(HttpStatus.FORBIDDEN, "La cuenta debe estar activa y ser administradora");
        }
        InventarioEspacio articulo = entidad.find(InventarioEspacio.class, articuloId,
                LockModeType.PESSIMISTIC_WRITE);
        if (articulo == null || articulo.getEspacio() == null
                || !espacioId.equals(articulo.getEspacio().getId())) {
            throw error(HttpStatus.NOT_FOUND, "El articulo no existe en este espacio");
        }
        // Conservador: cualquier reserva del espacio, incluso cancelada, bloquea.
        long reservas = contar("SELECT COUNT(*) FROM solicitudes_espacio WHERE espacio_id = :id", espacioId);
        long detalles = contar("SELECT COUNT(*) FROM solicitud_recursos WHERE inventario_id = :id", articuloId);
        if (reservas > 0 || detalles > 0) {
            throw error(HttpStatus.CONFLICT,
                    "No se puede eliminar: el espacio tiene reservas o el articulo tiene registros asociados. "
                    + "Se conserva el inventario y su historial");
        }
        entidad.remove(articulo);
        entidad.flush();
        // No elimina el espacio ni modifica activacion, cupos o documentos.
    }
    private long contar(String sql, Long id) {
        Object resultado = entidad.createNativeQuery(sql).setParameter("id", id).getSingleResult();
        if (!(resultado instanceof Number numero)) {
            throw new IllegalStateException("No se pudo comprobar el historial del inventario");
        }
        long cantidad;
        try { cantidad = new BigDecimal(numero.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalStateException("Conteo de historial invalido", ex);
        }
        if (cantidad < 0) { throw new IllegalStateException("Conteo de historial invalido"); }
        return cantidad;
    }
    private static ResponseStatusException error(HttpStatus estado, String mensaje) {
        return new ResponseStatusException(estado, mensaje);
    }
}
