package com.sistema.bitacora.supervisorrevision;

import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name="bitacora.supervisor.revision-habilitada", havingValue="true")
public class UrgenciaCupo {
    static final String REFUND = "UPDATE consumo_cupos_reserva SET fecha_reintegro=CURRENT_TIMESTAMP, reintegrado_por_usuario_id=:actor, motivo_reintegro=:motivo WHERE solicitud_id=:id AND fecha_reintegro IS NULL";
    private final EntityManager em;
    public UrgenciaCupo(EntityManager em) { this.em=em; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void reintegrar(long id, long actor, String motivo) {
        Object max = em.createNativeQuery("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='consumo_cupos_reserva' AND COLUMN_NAME='motivo_reintegro'").getSingleResult();
        if (!(max instanceof Number) || ((Number)max).longValue() < 255) throw UrgenciaPolicy.conflict("El esquema de reintegros requiere revisión; operación no aplicada");
        int changed = em.createNativeQuery(REFUND).setParameter("actor",actor).setParameter("motivo",motivo).setParameter("id",id).executeUpdate();
        if (changed != 1) throw UrgenciaPolicy.conflict("El consumo ya fue reintegrado o no existe; operación no aplicada");
        // Never update Departamento.cupoMensual. Original department and event month remain on the request.
    }
}