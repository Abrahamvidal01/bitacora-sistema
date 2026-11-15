package com.sistema.bitacora.supervisorrevision;

import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name="bitacora.supervisor.revision-habilitada", havingValue="true")
public class UrgenciaAuditWriter {
    private final EntityManager em;
    public UrgenciaAuditWriter(EntityManager em) { this.em=em; }
    // Separate proxied bean permits a test spy to fail AFTER the refund; no production test switch.
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(long id, long actor, UrgenciaPolicy.Command c, String signature, long revision,
                       String oldState, String oldDocs, UrgenciaPolicy.Transition next) {
        int changed = em.createNativeQuery("""
            INSERT INTO revision_urgencias_eventos
            (clave_operacion,solicitud_id,actor_usuario_id,firma_payload,version_esperada,
             revision_antes,revision_despues,estado_antes,estado_despues,documentos_antes,documentos_despues,
             accion,motivo,documentos_revisados,cupo_reintegrado)
            VALUES (:key,:id,:actor,:signature,:version,:before,:after,:oldState,:newState,:oldDocs,:newDocs,:action,:reason,:reviewed,:refund)
            """)
            .setParameter("key",c.clave()).setParameter("id",id).setParameter("actor",actor)
            .setParameter("signature",signature).setParameter("version",c.versionEsperada())
            .setParameter("before",revision).setParameter("after",Math.addExact(revision,1))
            .setParameter("oldState",oldState).setParameter("newState",next.estado())
            .setParameter("oldDocs",oldDocs).setParameter("newDocs",next.documentos())
            .setParameter("action",c.accion()).setParameter("reason",c.motivo())
            .setParameter("reviewed",c.documentosRevisados()).setParameter("refund",next.reintegrar()).executeUpdate();
        if (changed != 1) throw new IllegalStateException("Audit write failed");
        em.flush();
    }
}