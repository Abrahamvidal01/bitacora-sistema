package com.sistema.bitacora.service;
import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.InventarioEspacio;
import com.sistema.bitacora.entity.Rol;
import com.sistema.bitacora.entity.Usuario;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Unitarias: no prueban MariaDB, el proxy transaccional ni HTTP.
class InventarioEliminacionServiceTest {
    private EntityManager entidad;
    private InventarioEliminacionService servicio;
    private Espacio espacio;
    private InventarioEspacio articulo;
    private Usuario usuario;
    private TypedQuery<Usuario> consultaUsuario;
    @BeforeEach @SuppressWarnings("unchecked") void preparar() {
        entidad = mock(EntityManager.class); servicio = new InventarioEliminacionService(entidad);
        espacio = new Espacio(); espacio.setId(20L);
        articulo = new InventarioEspacio(); articulo.setId(30L); articulo.setEspacio(espacio);
        usuario = new Usuario(); usuario.setActivo(true);
        Rol rol = new Rol(); rol.setNombreRol("ADMINISTRADOR"); usuario.setRoles(Set.of(rol));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin@prueba.local", "no-usada", List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))));
        when(entidad.find(Espacio.class, 20L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(espacio);
        when(entidad.find(InventarioEspacio.class, 30L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(articulo);
        consultaUsuario = mock(TypedQuery.class);
        when(entidad.createQuery(anyString(), eq(Usuario.class))).thenReturn(consultaUsuario);
        when(consultaUsuario.setParameter(eq("correo"), any())).thenReturn(consultaUsuario);
        when(consultaUsuario.setLockMode(LockModeType.PESSIMISTIC_READ)).thenReturn(consultaUsuario);
        when(consultaUsuario.getResultList()).thenReturn(List.of(usuario));
    }
    @AfterEach void limpiarSesion() { SecurityContextHolder.clearContext(); }
    private Query consulta(Object valor, Long id) {
        Query q = mock(Query.class); when(q.setParameter("id", id)).thenReturn(q);
        when(q.getSingleResult()).thenReturn(valor); return q;
    }
    private void conteos(Object reservas, Object detalles) {
        Query r = consulta(reservas, 20L), d = consulta(detalles, 30L);
        when(entidad.createNativeQuery(contains("FROM solicitudes_espacio"))).thenReturn(r);
        when(entidad.createNativeQuery(contains("FROM solicitud_recursos"))).thenReturn(d);
    }
    private ResponseStatusException rechazado() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> servicio.eliminar(20L, 30L));
        verify(entidad, never()).remove(any()); verify(entidad, never()).flush(); return ex;
    }
    @Test void eliminaArticuloSinEliminarEspacio() {
        conteos(0L, 0L); servicio.eliminar(20L, 30L);
        var orden = inOrder(entidad);
        orden.verify(entidad).find(Espacio.class, 20L, LockModeType.PESSIMISTIC_WRITE);
        orden.verify(entidad).find(InventarioEspacio.class, 30L, LockModeType.PESSIMISTIC_WRITE);
        orden.verify(entidad).remove(articulo); orden.verify(entidad).flush();
        verify(entidad, never()).remove(espacio);
    }
    @Test void bloqueaCualquierReservaDelEspacio() {
        conteos(1L, 0L); assertEquals(409, rechazado().getStatusCode().value());
        verify(entidad).createNativeQuery("SELECT COUNT(*) FROM solicitudes_espacio WHERE espacio_id = :id");
    }
    @Test void bloqueaRecursosAsociadosAlArticulo() { conteos(0L, 1L); assertEquals(409, rechazado().getStatusCode().value()); }
    @Test void bloqueaArticuloDeOtroEspacio() {
        Espacio otro = new Espacio(); otro.setId(99L); articulo.setEspacio(otro);
        assertEquals(404, rechazado().getStatusCode().value()); verify(entidad, never()).createNativeQuery(anyString());
    }
    @Test void rechazaArticuloInexistente() {
        when(entidad.find(InventarioEspacio.class, 30L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(null);
        assertEquals(404, rechazado().getStatusCode().value());
    }
    @Test void rechazaEspacioInexistente() {
        when(entidad.find(Espacio.class, 20L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(null);
        assertEquals(404, rechazado().getStatusCode().value());
    }
    @Test void rechazaIdsInvalidosSinAccesoAEntidad() {
        assertThrows(ResponseStatusException.class, () -> servicio.eliminar(null, 30L));
        assertThrows(ResponseStatusException.class, () -> servicio.eliminar(0L, 30L));
        assertThrows(ResponseStatusException.class, () -> servicio.eliminar(20L, -1L));
        assertThrows(ResponseStatusException.class, () -> servicio.eliminar(20L, null)); verifyNoInteractions(entidad);
    }
    @Test void rechazaSinSesion() {
        SecurityContextHolder.clearContext(); assertEquals(403, rechazado().getStatusCode().value()); verifyNoInteractions(entidad);
    }
    @Test void rechazaCuentaInactiva() {
        usuario.setActivo(false); assertEquals(403, rechazado().getStatusCode().value()); verify(entidad, never()).createNativeQuery(anyString());
    }
    @Test void rechazaCuentaSinRol() {
        usuario.setRoles(Set.of()); assertEquals(403, rechazado().getStatusCode().value()); verify(entidad, never()).createNativeQuery(anyString());
    }
    @Test void rechazaCuentaNoRegistrada() {
        when(consultaUsuario.getResultList()).thenReturn(List.of()); assertEquals(403, rechazado().getStatusCode().value());
    }
    @Test void bloqueaConteoNoNumerico() {
        conteos("0", 0L); assertThrows(IllegalStateException.class, () -> servicio.eliminar(20L, 30L)); verify(entidad, never()).remove(any());
    }
    @Test void bloqueaConteoNegativo() {
        conteos(0L, -1L); assertThrows(IllegalStateException.class, () -> servicio.eliminar(20L, 30L)); verify(entidad, never()).remove(any());
    }
    @Test void bloqueaConteoFraccionario() {
        conteos(new BigDecimal("0.5"), 0L); assertThrows(IllegalStateException.class, () -> servicio.eliminar(20L, 30L)); verify(entidad, never()).remove(any());
    }
    @Test void propagaErrorDeFlushParaRollback() {
        conteos(0L, 0L); doThrow(new IllegalStateException("simulado")).when(entidad).flush();
        assertThrows(IllegalStateException.class, () -> servicio.eliminar(20L, 30L));
    }
}
