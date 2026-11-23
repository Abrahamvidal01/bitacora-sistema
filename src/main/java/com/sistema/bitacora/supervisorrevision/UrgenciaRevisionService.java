package com.sistema.bitacora.supervisorrevision;

import com.sistema.bitacora.entity.*;
import com.sistema.bitacora.repository.UsuarioRepository;
import jakarta.persistence.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

@Service
@ConditionalOnProperty(name="bitacora.supervisor.revision-habilitada", havingValue="true")
@PreAuthorize("hasRole('SUPERVISOR')")
public class UrgenciaRevisionService {
    private final EntityManager em;
    private final UsuarioRepository usuarios;
    private final UrgenciaDocumentos documents;
    private final UrgenciaCupo cupo;
    private final UrgenciaAuditWriter audit;
    private final Clock clock;
    public UrgenciaRevisionService(EntityManager em, UsuarioRepository usuarios, UrgenciaDocumentos documents,
            UrgenciaCupo cupo, UrgenciaAuditWriter audit, @Qualifier("relojReservas") Clock clock) {
        this.em=em; this.usuarios=usuarios; this.documents=documents; this.cupo=cupo; this.audit=audit; this.clock=clock;
    }
    public record Resource(long inventarioId,int cantidad,String nombre,String estadoHistorico) {}
    public record Row(long id,String folio,String solicitante,long departamentoOriginalId,String departamentoOriginal,
                      long espacioId,String espacio,String fecha,String inicio,String fin,
                      String motivo,String recursosExternos,String modalidad,String estado,String documentos,long revision,String version,
                      boolean oficioRegistrado,boolean requisicionRegistrada,List<Resource> recursos) {}
    public record Month(String mes,int total,long pendientes,long aprobadas,long rechazadas,List<Row> solicitudes) {}
    public record Result(long solicitudId,long revision,String estado,String documentos,boolean repetida) {}
    public record Event(long revision,String clave,long actorId,String actor,String fecha,String accion,String motivo,
                        String estadoAntes,String estadoDespues,String documentosAntes,String documentosDespues,boolean reintegrado) {}
    private record Last(long revision,String docs) {}
    private ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Reserva urgente no disponible"); }
    private Usuario actor(boolean lock) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Inicia sesión");
        Usuario u = usuarios.findByCorreoInstitucional(a.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,"Cuenta no autorizada"));
        if (lock) em.refresh(u,LockModeType.PESSIMISTIC_WRITE); else em.refresh(u);
        checkActor(u,a); return u;
    }
    private void checkActor(Usuario u,Authentication a) {
        Set<String> session = new HashSet<>(); a.getAuthorities().forEach(x -> session.add(x.getAuthority()));
        if (!Boolean.TRUE.equals(u.getActivo()) || u.getRoles()==null || u.getRoles().stream().noneMatch(r -> r!=null &&
                "SUPERVISOR".equals(r.getNombreRol()) && session.contains("ROLE_"+r.getNombreRol())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"La cuenta debe estar activa y conservar un rol autorizado en la sesión y en la base de datos");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true,timeout=30)
    public void checkAccess() { actor(false); }
    private String mode(long id) {
        List<?> rows = em.createNativeQuery("SELECT modalidad FROM confirmaciones_reserva WHERE solicitud_id=:id").setParameter("id",id).getResultList();
        if (rows.size()!=1 || !UrgenciaPolicy.urgent(String.valueOf(rows.get(0)))) throw missing();
        return String.valueOf(rows.get(0));
    }
    private SolicitudEspacio request(long id) {
        if (id<=0) throw missing();
        SolicitudEspacio s=em.find(SolicitudEspacio.class,id); if (s==null) throw missing(); return s;
    }
    private Last last(SolicitudEspacio s) {
        List<?> rows=em.createNativeQuery("SELECT revision_despues,documentos_despues FROM revision_urgencias_eventos WHERE solicitud_id=:id ORDER BY revision_despues DESC")
                .setParameter("id",s.getId()).setMaxResults(1).getResultList();
        if (rows.isEmpty()) return new Last(0,"RECHAZADA".equals(s.getEstatusSolicitud())?"NO_APLICA":"PENDIENTE");
        Object[] r=(Object[])rows.get(0); return new Last(((Number)r[0]).longValue(),String.valueOf(r[1]));
    }
    private String immutable(SolicitudEspacio s) {
        return UrgenciaPolicy.hash(s.getId(),s.getFolio(),s.getUsuario().getId(),s.getDepartamentoSolicitante().getId(),
                s.getNombramiento().getId(),s.getRegistradoPor().getId(),s.getEspacio().getId(),s.getFechaEvento(),s.getHoraInicio(),s.getHoraFin(),
                s.getMotivoEvento(),s.getRecursosExternos(),s.getRutaOficioReservacion(),s.getRutaRequisicion(),s.getRutaOficioRequisicion(),
                s.getEsUrgenteSinOficio(),s.getTipoOrigen(),s.getFechaSolicitud());
    }
    private List<Resource> resources(long id) {
        List<?> rows=em.createNativeQuery("SELECT inventario_id,cantidad_solicitada,nombre_item,estado_bien FROM solicitud_recursos WHERE solicitud_id=:id ORDER BY inventario_id")
                .setParameter("id",id).getResultList();
        List<Resource> result=new ArrayList<>();
        for (Object value:rows) { Object[] r=(Object[])value; result.add(new Resource(((Number)r[0]).longValue(),((Number)r[1]).intValue(),String.valueOf(r[2]),String.valueOf(r[3]))); }
        return List.copyOf(result);
    }
    private String version(SolicitudEspacio s,String mode,Last last,List<Resource> resources) {
        List<Object> parts=new ArrayList<>(); Collections.addAll(parts,"snapshot-v1",immutable(s),s.getEstatusSolicitud(),mode,last.revision(),last.docs());
        for(Resource r:resources) Collections.addAll(parts,r.inventarioId(),r.cantidad(),r.nombre(),r.estadoHistorico());
        return UrgenciaPolicy.hash(parts.toArray());
    }
    private Row row(SolicitudEspacio s) {
        String mode=mode(s.getId()); Last last=last(s); List<Resource> r=resources(s.getId());
        return new Row(s.getId(),s.getFolio(),s.getUsuario().getNombreCompleto(),s.getDepartamentoSolicitante().getId(),s.getDepartamentoSolicitante().getNombreDepartamento(),
                s.getEspacio().getId(),s.getEspacio().getNombreEspacio(),s.getFechaEvento().toString(),s.getHoraInicio().toString(),s.getHoraFin().toString(),
                s.getMotivoEvento(),s.getRecursosExternos(),mode,s.getEstatusSolicitud(),last.docs(),last.revision(),version(s,mode,last,r),
                s.getRutaOficioReservacion()!=null,s.getRutaRequisicion()!=null,r);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ,readOnly=true,timeout=30)
    public Month list(String month) {
        actor(false);
        YearMonth m;
        try { if(month==null || !month.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException(); m=YearMonth.parse(month); if(m.getYear()<1000 || m.getYear()>9998) throw new IllegalArgumentException(); }
        catch(RuntimeException e) { throw UrgenciaPolicy.bad("Indica un mes válido (AAAA-MM)"); }
        List<?> ids=em.createNativeQuery("SELECT s.id FROM solicitudes_espacio s JOIN confirmaciones_reserva c ON c.solicitud_id=s.id WHERE c.modalidad IN ('URGENTE_REQUISICION','URGENTE_INVENTARIO') AND s.fecha_evento>=:start AND s.fecha_evento<:end ORDER BY s.fecha_evento,s.hora_inicio,s.id")
                .setParameter("start",m.atDay(1)).setParameter("end",m.plusMonths(1).atDay(1)).setMaxResults(2001).getResultList();
        if(ids.size()>2000) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"El mes supera 2000 reservas urgentes; no se devuelve una lista parcial ni conteos incompletos");
        List<Row> rows=new ArrayList<>(); for(Object id:ids) rows.add(row(request(((Number)id).longValue())));
        return new Month(m.toString(),rows.size(),rows.stream().filter(r->"PENDIENTE".equals(r.estado())).count(),rows.stream().filter(r->"APROBADA".equals(r.estado())).count(),rows.stream().filter(r->"RECHAZADA".equals(r.estado())).count(),List.copyOf(rows));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true,timeout=30)
    public List<Event> history(long id) {
        actor(false); request(id); mode(id);
        List<?> rows=em.createNativeQuery("SELECT revision_despues,clave_operacion,actor_usuario_id,fecha_registro,accion,motivo,estado_antes,estado_despues,documentos_antes,documentos_despues,cupo_reintegrado FROM revision_urgencias_eventos WHERE solicitud_id=:id ORDER BY revision_despues")
                .setParameter("id",id).setMaxResults(2001).getResultList();
        if(rows.size()>2000) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Historial demasiado grande; no se devuelve un historial parcial");
        List<Event> result=new ArrayList<>();
        for(Object value:rows) { Object[] r=(Object[])value; long actorId=((Number)r[2]).longValue(); Usuario u=em.find(Usuario.class,actorId);
            boolean refunded=Boolean.TRUE.equals(r[10]) || (r[10] instanceof Number n && n.intValue()!=0);
            result.add(new Event(((Number)r[0]).longValue(),String.valueOf(r[1]),actorId,u==null?"Cuenta histórica":u.getNombreCompleto(),String.valueOf(r[3]),String.valueOf(r[4]),String.valueOf(r[5]),String.valueOf(r[6]),String.valueOf(r[7]),String.valueOf(r[8]),String.valueOf(r[9]),refunded)); }
        return List.copyOf(result);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,readOnly=true,timeout=30)
    public byte[] document(long id,String type) {
        actor(false); SolicitudEspacio s=request(id); mode(id);
        if (!Set.of("oficio","requisicion").contains(type)) throw UrgenciaPolicy.bad("Tipo de documento no válido");
        return documents.read("oficio".equals(type)?s.getRutaOficioReservacion():s.getRutaRequisicion());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class,timeout=30)
    public Result apply(long id,UrgenciaPolicy.Command raw) {
        UrgenciaPolicy.Command c=UrgenciaPolicy.validate(raw);
        Usuario actor=actor(false);
        SolicitudEspacio s=request(id); String originalMode=mode(id); String original=immutable(s);
        Long dept=s.getDepartamentoSolicitante().getId(), space=s.getEspacio().getId();
        // Coordinate with confirmation: historical department -> space -> actor -> request -> sorted inventory.
        Departamento department=em.find(Departamento.class,dept); if(department==null) throw UrgenciaPolicy.conflict("El departamento histórico no está disponible");
        em.refresh(department,LockModeType.PESSIMISTIC_WRITE);
        Espacio room=em.find(Espacio.class,space); if(room==null) throw UrgenciaPolicy.conflict("El espacio no está disponible");
        em.refresh(room,LockModeType.PESSIMISTIC_WRITE);
        em.refresh(actor,LockModeType.PESSIMISTIC_WRITE);
        checkActor(actor,SecurityContextHolder.getContext().getAuthentication());
        em.refresh(s,LockModeType.PESSIMISTIC_WRITE);
        if(!original.equals(immutable(s)) || !originalMode.equals(mode(id))) throw UrgenciaPolicy.conflict("Cambiaron los datos históricos de la reserva; vuelve a consultar");
        String signature=UrgenciaPolicy.signature(id,actor.getId(),c);
        List<?> repeated=em.createNativeQuery("SELECT solicitud_id,actor_usuario_id,firma_payload,revision_despues,estado_despues,documentos_despues FROM revision_urgencias_eventos WHERE clave_operacion=:key")
                .setParameter("key",c.clave()).getResultList();
        if(!repeated.isEmpty()) {
            Object[] r=(Object[])repeated.get(0);
            UrgenciaPolicy.checkReplay(id,actor.getId(),signature,((Number)r[0]).longValue(),((Number)r[1]).longValue(),String.valueOf(r[2]));
            return new Result(id,((Number)r[3]).longValue(),String.valueOf(r[4]),String.valueOf(r[5]),true);
        }
        Last last=last(s); List<Resource> resources=resources(id);
        UrgenciaPolicy.checkVersion(c.versionEsperada(),version(s,originalMode,last,resources));
        UrgenciaPolicy.Transition next=UrgenciaPolicy.transition(originalMode,s.getEstatusSolicitud(),c);
        List<?> consumption=em.createNativeQuery("SELECT fecha_reintegro FROM consumo_cupos_reserva WHERE solicitud_id=:id FOR UPDATE").setParameter("id",id).getResultList();
        if(consumption.size()!=1 || consumption.get(0)!=null) throw UrgenciaPolicy.conflict("La reserva no conserva un consumo de cupo pendiente de reintegro");
        if("APROBAR".equals(c.accion())) validateApproval(s,room,resources);
        if("APROBAR".equals(c.accion()) || "VALIDAR_DOCUMENTOS".equals(c.accion())) {
            documents.read(s.getRutaOficioReservacion());
            if("URGENTE_REQUISICION".equals(originalMode) || s.getRutaRequisicion()!=null) documents.read(s.getRutaRequisicion());
        }
        String old=s.getEstatusSolicitud();
        if(next.reintegrar()) cupo.reintegrar(id,actor.getId(),c.motivo());
        s.setEstatusSolicitud(next.estado()); em.flush();
        audit.append(id,actor.getId(),c,signature,last.revision(),old,last.docs(),next);
        return new Result(id,last.revision()+1,next.estado(),next.documentos(),false);
    }
    private void validateApproval(SolicitudEspacio s,Espacio room,List<Resource> resources) {
        LocalDateTime start=s.getFechaEvento().atTime(s.getHoraInicio()), end=s.getFechaEvento().atTime(s.getHoraFin());
        if(!end.isAfter(start) || !start.isAfter(LocalDateTime.now(clock))) throw UrgenciaPolicy.conflict("La aprobación requiere un evento futuro con horario válido");
        // The 72-hour capture rule is intentionally NOT re-applied at decision time.
        if(!Boolean.TRUE.equals(room.getActivo()) || !"DISPONIBLE".equals(String.valueOf(room.getEstadoActual()))) throw UrgenciaPolicy.conflict("El espacio no está activo y disponible");
        int clean=UrgenciaPolicy.requireCleaning(room.getMinutosLimpieza());
        int days=clean/1440+2;
        List<SolicitudEspacio> neighbours=em.createQuery("select s from SolicitudEspacio s where s.espacio.id=:space and s.id<>:id and s.fechaEvento>=:from and s.fechaEvento<=:to and (s.estatusSolicitud is null or s.estatusSolicitud not in ('CANCELADA','RECHAZADA'))",SolicitudEspacio.class)
                .setParameter("space",room.getId()).setParameter("id",s.getId()).setParameter("from",s.getFechaEvento().minusDays(days)).setParameter("to",s.getFechaEvento().plusDays(days)).getResultList();
        for(SolicitudEspacio other:neighbours) {
            if(other.getHoraInicio()==null || other.getHoraFin()==null || !other.getHoraFin().isAfter(other.getHoraInicio())) throw UrgenciaPolicy.conflict("Hay un horario vecino inconsistente");
            if(UrgenciaPolicy.overlap(start,end,other.getFechaEvento().atTime(other.getHoraInicio()),other.getFechaEvento().atTime(other.getHoraFin()),clean)) throw UrgenciaPolicy.conflict("El horario o el margen de limpieza se cruza con otra reserva");
        }
        Set<Long> seen=new HashSet<>();
        for(Resource r:resources) {
            if(r.inventarioId()<=0 || r.cantidad()<=0 || !seen.add(r.inventarioId())) throw UrgenciaPolicy.conflict("Los recursos históricos son inconsistentes");
            InventarioEspacio item=em.find(InventarioEspacio.class,r.inventarioId()); if(item==null) throw UrgenciaPolicy.conflict("Un recurso ya no está disponible");
            em.refresh(item,LockModeType.PESSIMISTIC_WRITE);
            if(item.getEspacio()==null || !room.getId().equals(item.getEspacio().getId()) || item.getCantidad()==null || item.getCantidad()<r.cantidad() ||
                    !Set.of("BUENO","REGULAR").contains(item.getEstadoBien())) throw UrgenciaPolicy.conflict("El inventario actual no permite aprobar los recursos de la reserva");
        }
    }
}