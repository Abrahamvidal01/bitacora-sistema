package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Espacio;
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

// Unitarias: no arrancan Spring ni conectan a ninguna base de datos.
class EspacioEliminacionServiceTest {
    private EntityManager entidad;
    private EspacioEliminacionService servicio;
    private Espacio espacio;
    private Usuario usuario;
    private TypedQuery<Usuario> consultaUsuario;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparar() {
        entidad = mock(EntityManager.class);
        servicio = new EspacioEliminacionService(entidad);
        espacio = new Espacio();
        usuario = new Usuario();
        usuario.setActivo(true);
        Rol rol = new Rol();
        rol.setNombreRol("ADMINISTRADOR");
        usuario.setRoles(Set.of(rol));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@prueba.local", "no-usada",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))));
        when(entidad.find(Espacio.class, 20L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(espacio);
        consultaUsuario = mock(TypedQuery.class);
        when(entidad.createQuery(anyString(), eq(Usuario.class))).thenReturn(consultaUsuario);
        when(consultaUsuario.setParameter(eq("correo"), any())).thenReturn(consultaUsuario);
        when(consultaUsuario.setLockMode(LockModeType.PESSIMISTIC_READ)).thenReturn(consultaUsuario);
        when(consultaUsuario.getResultList()).thenReturn(List.of(usuario));
    }

    @AfterEach
    void limpiarSesion() { SecurityContextHolder.clearContext(); }

    private void conteos(Object reservas, Object inventario, Object recursos) {
        Query r = contar(reservas), i = contar(inventario), d = contar(recursos);
        when(entidad.createNativeQuery(contains("FROM solicitudes_espacio"))).thenReturn(r);
        when(entidad.createNativeQuery(contains("FROM inventario_espacios"))).thenReturn(i);
        when(entidad.createNativeQuery(contains("FROM solicitud_recursos"))).thenReturn(d);
    }

    private Query contar(Object valor) {
        Query consulta = mock(Query.class);
        when(consulta.setParameter("id", 20L)).thenReturn(consulta);
        when(consulta.getSingleResult()).thenReturn(valor);
        return consulta;
    }

    private void bloqueado() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> servicio.eliminar(20L));
        assertEquals(409, ex.getStatusCode().value());
        verify(entidad, never()).remove(any());
        verify(entidad, never()).flush();
    }

    @Test void eliminaSoloVacioYHaceFlush() {
        conteos(0L, 0L, 0L);
        servicio.eliminar(20L);
        var orden = inOrder(entidad);
        orden.verify(entidad).find(Espacio.class, 20L, LockModeType.PESSIMISTIC_WRITE);
        orden.verify(entidad).remove(espacio);
        orden.verify(entidad).flush();
    }
    @Test void bloqueaCualquierReservaSinFiltrarEstados() {
        conteos(1L, 0L, 0L); bloqueado();
        verify(entidad).createNativeQuery("SELECT COUNT(*) FROM solicitudes_espacio WHERE espacio_id = :id");
    }
    @Test void bloqueaInventarioAunqueSuFkPermitaCascada() { conteos(0L, 1L, 0L); bloqueado(); }
    @Test void bloqueaDetalleDeRecursos() { conteos(0L, 0L, 1L); bloqueado(); }
    @Test void rechazaIdNulo() {
        assertThrows(ResponseStatusException.class, () -> servicio.eliminar(null));
        verifyNoInteractions(entidad);
    }
    @Test void rechazaIdCero() {
        assertThrows(ResponseStatusException.class, () -> servicio.eliminar(0L));
        verifyNoInteractions(entidad);
    }
    @Test void rechazaSinSesion() {
        SecurityContextHolder.clearContext();
        assertThrows(ResponseStatusException.class, () -> servicio.eliminar(20L));
        verifyNoInteractions(entidad);
    }
    @Test void rechazaEspacioInexistente() {
        when(entidad.find(Espacio.class, 20L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> servicio.eliminar(20L));
        assertEquals(404, ex.getStatusCode().value());
        verify(entidad, never()).remove(any());
    }
    @Test void rechazaCuentaInexistente() {
        when(consultaUsuario.getResultList()).thenReturn(List.of());
        assertThrows(ResponseStatusException.class, () -> servicio.eliminar(20L));
        verify(entidad, never()).createNativeQuery(anyString());
    }
    @Test void rechazaCuentaInactiva() {
        usuario.setActivo(false);
        assertThrows(ResponseStatusException.class, () -> servicio.eliminar(20L));
        verify(entidad, never()).createNativeQuery(anyString());
    }
    @Test void rechazaCuentaSinRolAdministrador() {
        usuario.setRoles(Set.of());
        assertThrows(ResponseStatusException.class, () -> servicio.eliminar(20L));
        verify(entidad, never()).createNativeQuery(anyString());
    }
    @Test void fallaCerradoSiConteoNoEsNumero() {
        conteos("0", 0L, 0L);
        assertThrows(IllegalStateException.class, () -> servicio.eliminar(20L));
        verify(entidad, never()).remove(any());
    }
    @Test void fallaCerradoSiConteoEsNegativo() {
        conteos(-1L, 0L, 0L);
        assertThrows(IllegalStateException.class, () -> servicio.eliminar(20L));
        verify(entidad, never()).remove(any());
    }
    @Test void fallaCerradoSiConteoEsFraccionario() {
        conteos(new BigDecimal("0.5"), 0L, 0L);
        assertThrows(IllegalStateException.class, () -> servicio.eliminar(20L));
        verify(entidad, never()).remove(any());
    }
    @Test void propagaFalloDeFlushParaQueSpringRevierta() {
        conteos(0L, 0L, 0L);
        doThrow(new IllegalStateException("simulado")).when(entidad).flush();
        assertThrows(IllegalStateException.class, () -> servicio.eliminar(20L));
    }
}
