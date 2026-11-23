package com.sistema.bitacora.supervisorbitacora;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** BO3: solo asignacion operativa. No cambia aprobacion, documentos, cupos ni avisos. */
@Service
public class BitacoraAsignacionService {
    private final EntityManager em;
    private final Clock clock;
    public BitacoraAsignacionService(EntityManager em, @Qualifier("relojReservas") Clock clock) {
        this.em=em; this.clock=clock;
    }
    public record Tomar(String clave) {}
    public record Resultado(long solicitudId,long supervisorId,String fechaUtc,boolean repetida) {}

    @PreAuthorize("hasRole('SUPERVISOR')")
    @Transactional(isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class, timeout=20)
    public Resultado tomar(long solicitudId,Tomar comando,Authentication autenticacion) {
        if (autenticacion==null || !autenticacion.isAuthenticated() || autenticacion.getName()==null) {
            throw error(HttpStatus.FORBIDDEN,"Acceso no autorizado");
        }
        if (solicitudId<=0 || comando==null || comando.clave()==null ||
            !comando.clave().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw error(HttpStatus.BAD_REQUEST,"Solicitud o clave no valida");
        }
        Number valor=(Number)em.createNativeQuery("""
            SELECT COALESCE(MAX(u.id),0) FROM usuarios u
            JOIN usuario_roles ur ON ur.usuario_id=u.id
            JOIN roles r ON r.id=ur.rol_id
            WHERE u.correo_institucional=:correo AND u.activo=1 AND r.nombre_rol='SUPERVISOR'
            """).setParameter("correo",autenticacion.getName()).getSingleResult();
        long actor=valor.longValue();
        if(actor<=0)throw error(HttpStatus.FORBIDDEN,"Acceso no autorizado");
        String firma=firma(solicitudId,actor);
        Resultado anterior=replay(comando.clave(),solicitudId,actor,firma);
        if(anterior!=null)return anterior;
        @SuppressWarnings("unchecked")
        List<Object[]> reservas=em.createNativeQuery("""
            SELECT id,estatus_solicitud FROM solicitudes_espacio WHERE id=:id FOR UPDATE
            """).setParameter("id",solicitudId).getResultList();
        if(reservas.isEmpty())throw error(HttpStatus.NOT_FOUND,"Reserva no disponible");
        if(reservas.size()!=1)throw new IllegalStateException("Reserva ambigua");
        // Otra operacion pudo concluir mientras se esperaba el bloqueo de la solicitud.
        anterior=replay(comando.clave(),solicitudId,actor,firma);
        if(anterior!=null)return anterior;
        if(!"APROBADA".equals(reservas.getFirst()[1])) {
            throw error(HttpStatus.CONFLICT,"Solo puede tomarse una reserva aprobada");
        }
        @SuppressWarnings("unchecked")
        List<Number> responsables=em.createNativeQuery("""
            SELECT supervisor_usuario_id FROM bitacora_operativa_asignaciones WHERE solicitud_id=:id
            """).setParameter("id",solicitudId).getResultList();
        if(!responsables.isEmpty()) {
            long responsable=responsables.getFirst().longValue();
            throw error(HttpStatus.CONFLICT,responsable==actor
                ? "La reserva ya esta asignada a tu cuenta; consulta su preparacion"
                : "La reserva ya tiene supervisor responsable; no se modifico la asignacion");
        }
        LocalDateTime ahora=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        int insertados=em.createNativeQuery("""
            INSERT INTO bitacora_operativa_asignaciones
            (solicitud_id,supervisor_usuario_id,clave_operacion,firma_payload,fecha_asignacion_utc)
            VALUES (:id,:actor,:clave,:firma,:fecha)
            """).setParameter("id",solicitudId).setParameter("actor",actor)
            .setParameter("clave",comando.clave()).setParameter("firma",firma)
            .setParameter("fecha",ahora).executeUpdate();
        if(insertados!=1)throw new IllegalStateException("No se confirmo una sola asignacion");
        em.flush();
        return new Resultado(solicitudId,actor,ahora.toString()+"Z",false);
    }
    private Resultado replay(String clave,long solicitud,long actor,String firma) {
        @SuppressWarnings("unchecked")
        List<Object[]> filas=em.createNativeQuery("""
            SELECT solicitud_id,supervisor_usuario_id,firma_payload,
                   DATE_FORMAT(fecha_asignacion_utc,'%Y-%m-%dT%H:%i:%s.%f')
            FROM bitacora_operativa_asignaciones WHERE clave_operacion=:clave
            """).setParameter("clave",clave).getResultList();
        if(filas.isEmpty())return null;
        if(filas.size()!=1)throw new IllegalStateException("Clave ambigua");
        Object[] f=filas.getFirst();
        if(((Number)f[0]).longValue()!=solicitud || ((Number)f[1]).longValue()!=actor || !firma.equals(f[2])) {
            throw error(HttpStatus.CONFLICT,"La clave de operacion ya se utilizo con otros datos o cuenta");
        }
        return new Resultado(solicitud,actor,f[3].toString()+"Z",true);
    }
    static String firma(long solicitud,long actor) {
        String texto="BO3:TOMAR_PREPARACION:v1\nsolicitud="+solicitud+"\nactor="+actor;
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 no disponible");}
    }
    private static ResponseStatusException error(HttpStatus estado,String mensaje) {
        return new ResponseStatusException(estado,mensaje);
    }
}
