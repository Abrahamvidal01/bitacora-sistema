package com.sistema.bitacora.supervisorrevision;
import jakarta.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class UrgenciaCupoTest {
    private EntityManager em=mock(EntityManager.class);
    private Query update=mock(Query.class), metadata=mock(Query.class);
    private UrgenciaCupo setup(int changed,long length) {
        when(em.createNativeQuery(startsWith("SELECT CHARACTER_MAXIMUM_LENGTH"))).thenReturn(metadata);
        when(metadata.getSingleResult()).thenReturn(length);
        when(em.createNativeQuery(UrgenciaCupo.REFUND)).thenReturn(update);
        when(update.setParameter(anyString(),any())).thenReturn(update);
        when(update.executeUpdate()).thenReturn(changed);
        return new UrgenciaCupo(em);
    }
    @Test void refundUsesOnlyConditionalConsumptionUpdate() {assertDoesNotThrow(()->setup(1,255).reintegrar(10,20,"Motivo"));verify(update).setParameter("id",10L);verify(update).setParameter("actor",20L);verify(update).executeUpdate();verify(em,never()).createQuery(anyString());assertTrue(UrgenciaCupo.REFUND.endsWith("AND fecha_reintegro IS NULL"));}
    @Test void secondRefundFailsClosed() {assertThrows(ResponseStatusException.class,()->setup(0,255).reintegrar(10,20,"Motivo"));}
    @Test void shortSchemaFailsBeforeUpdate() {assertThrows(ResponseStatusException.class,()->setup(1,100).reintegrar(10,20,"Motivo"));verify(update,never()).executeUpdate();}
}