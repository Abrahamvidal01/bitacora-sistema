package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Departamento;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// Pruebas unitarias con EntityManager simulado.
// No ejecutan el SQL en MariaDB ni prueban bloqueos.
class ReservaCupoValidacionServiceTest {

    private static final Long DEPARTAMENTO_ID = 1L;

    private static final LocalDate FECHA =
            LocalDate.of(2026, 11, 12);

    private EntityManager entityManager;
    private Query consulta;
    private Departamento departamento;
    private ReservaCupoValidacionService servicio;

    @BeforeEach
    void preparar() {
        entityManager = mock(EntityManager.class);
        consulta = mock(Query.class);

        departamento = new Departamento();
        departamento.setId(DEPARTAMENTO_ID);
        departamento.setNombreDepartamento("Área de prueba");
        departamento.setActivo(true);
        departamento.setCupoMensual(1);

        servicio = new ReservaCupoValidacionService(entityManager);

        when(entityManager.find(
                Departamento.class,
                DEPARTAMENTO_ID
        )).thenReturn(departamento);

        when(entityManager.createNativeQuery(anyString()))
                .thenReturn(consulta);

        when(consulta.getSingleResult()).thenReturn(0L);
    }

    @Test
    void permiteUnCupoCuandoNoHayConsumos() {
        var resultado = servicio.validar(DEPARTAMENTO_ID, FECHA);

        assertEquals(DEPARTAMENTO_ID, resultado.departamentoId());
        assertEquals("2026-11", resultado.mesEvento());
        assertEquals(1, resultado.cupoMensual());
        assertEquals(0L, resultado.consumidos());
        assertEquals(1L, resultado.disponibles());
    }

    @Test
    void rechazaCupoUnoCuandoYaExisteUnConsumo() {
        when(consulta.getSingleResult()).thenReturn(1L);

        assertEquals(409, errorDeValidacion().getStatusCode().value());
    }

    @Test
    void permiteElCuartoCupoCuandoHayTresConsumos() {
        departamento.setCupoMensual(4);
        when(consulta.getSingleResult()).thenReturn(3L);

        var resultado = servicio.validar(DEPARTAMENTO_ID, FECHA);

        assertEquals(4, resultado.cupoMensual());
        assertEquals(3L, resultado.consumidos());
        assertEquals(1L, resultado.disponibles());
    }

    @Test
    void rechazaCupoCuatroCuandoYaHayCuatroConsumos() {
        departamento.setCupoMensual(4);
        when(consulta.getSingleResult()).thenReturn(4L);

        assertEquals(409, errorDeValidacion().getStatusCode().value());
    }

    @Test
    void rechazaSiLosConsumosSuperanElLimite() {
        departamento.setCupoMensual(4);
        when(consulta.getSingleResult()).thenReturn(5L);

        assertEquals(409, errorDeValidacion().getStatusCode().value());
    }

    @Test
    void consultaTodoFebreroDeUnAnioBisiesto() {
        servicio.validar(
                DEPARTAMENTO_ID,
                LocalDate.of(2028, 2, 15)
        );

        verify(consulta).setParameter(
                "departamentoId",
                DEPARTAMENTO_ID
        );

        verify(consulta).setParameter(
                "desde",
                LocalDate.of(2028, 2, 1)
        );

        verify(consulta).setParameter(
                "hasta",
                LocalDate.of(2028, 2, 29)
        );
    }

    @Test
    void utilizaElMesDelEventoInclusoEnOtroAnio() {
        var resultado = servicio.validar(
                DEPARTAMENTO_ID,
                LocalDate.of(2027, 12, 20)
        );

        assertEquals("2027-12", resultado.mesEvento());

        verify(consulta).setParameter(
                "desde",
                LocalDate.of(2027, 12, 1)
        );

        verify(consulta).setParameter(
                "hasta",
                LocalDate.of(2027, 12, 31)
        );
    }

    @Test
    void rechazaDepartamentoNuloSinConsultarLaBase() {
        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validar(null, FECHA)
        );

        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(entityManager, consulta);
    }

    @Test
    void rechazaFechaNulaSinConsultarLaBase() {
        var error = assertThrows(
                ResponseStatusException.class,
                () -> servicio.validar(DEPARTAMENTO_ID, null)
        );

        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(entityManager, consulta);
    }

    @Test
    void rechazaDepartamentoInexistente() {
        when(entityManager.find(
                Departamento.class,
                DEPARTAMENTO_ID
        )).thenReturn(null);

        assertEquals(404, errorDeValidacion().getStatusCode().value());
        verifyNoInteractions(consulta);
    }

    @Test
    void rechazaDepartamentoDesactivado() {
        departamento.setActivo(false);

        assertEquals(409, errorDeValidacion().getStatusCode().value());
        verifyNoInteractions(consulta);
    }

    @Test
    void rechazaUnLimiteNoPermitido() {
        departamento.setCupoMensual(2);

        assertEquals(409, errorDeValidacion().getStatusCode().value());
        verifyNoInteractions(consulta);
    }

    @Test
    void rechazaCupoSinConfigurar() {
        departamento.setCupoMensual(null);

        assertEquals(409, errorDeValidacion().getStatusCode().value());
        verifyNoInteractions(consulta);
    }

    @Test
    void rechazaUnConteoNegativo() {
        when(consulta.getSingleResult()).thenReturn(-1L);

        assertEquals(409, errorDeValidacion().getStatusCode().value());
    }

    @Test
    void rechazaUnResultadoQueNoSeaNumerico() {
        when(consulta.getSingleResult()).thenReturn("conteo incorrecto");

        assertEquals(409, errorDeValidacion().getStatusCode().value());
    }

    private ResponseStatusException errorDeValidacion() {
        return assertThrows(
                ResponseStatusException.class,
                () -> servicio.validar(DEPARTAMENTO_ID, FECHA)
        );
    }
}
