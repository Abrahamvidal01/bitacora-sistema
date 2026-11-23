package com.sistema.bitacora.supervisorbitacora;

import jakarta.persistence.EntityManager;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** BO3-B: candidatos minimos + toma idempotente. No registra preparacion ni avisos. */
@RestController
@RequestMapping("/supervisor/api/bitacora-asignaciones")
@PreAuthorize("hasRole('SUPERVISOR')")
public class BitacoraAsignacionController {
    private final EntityManager em;
    private final BitacoraAsignacionService service;
    public BitacoraAsignacionController(EntityManager em,BitacoraAsignacionService service){this.em=em;this.service=service;}
    public record Candidata(long id,String folio,String fecha,String inicio,String fin,String espacio,String asignacion){}
    public record Mes(String mes,List<Candidata> reservas){}
    public record Token(String headerName,String token){}
    public record Fallo(String mensaje,boolean resultadoIncierto){}
    private long actor(Authentication auth){
        if(auth==null||!auth.isAuthenticated()||auth.getName()==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Acceso no autorizado");
        Number id=(Number)em.createNativeQuery("""
            SELECT COALESCE(MAX(u.id),0) FROM usuarios u
            JOIN usuario_roles ur ON ur.usuario_id=u.id
            JOIN roles r ON r.id=ur.rol_id
            WHERE u.correo_institucional=:correo AND u.activo=1 AND r.nombre_rol='SUPERVISOR'
            """).setParameter("correo",auth.getName()).getSingleResult();
        if(id.longValue()<=0)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Acceso no autorizado");
        return id.longValue();
    }
    @GetMapping("/csrf")
    @Transactional(readOnly=true,timeout=20)
    public ResponseEntity<Token> csrf(Authentication auth,CsrfToken token){
        actor(auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Token(token.getHeaderName(),token.getToken()));
    }
    @GetMapping
    @Transactional(readOnly=true,timeout=20)
    public ResponseEntity<Mes> candidatas(@RequestParam("mes")String mes,Authentication auth){
        long actor=actor(auth);
        YearMonth periodo=BitacoraOperativaConsultaController.validarMes(mes);
        @SuppressWarnings("unchecked")
        List<Object[]> filas=em.createNativeQuery("""
            SELECT s.id,s.folio,DATE_FORMAT(s.fecha_evento,'%Y-%m-%d'),
                   TIME_FORMAT(s.hora_inicio,'%H:%i'),TIME_FORMAT(s.hora_fin,'%H:%i'),
                   e.nombre_espacio,a.supervisor_usuario_id
            FROM solicitudes_espacio s JOIN espacios e ON e.id=s.espacio_id
            LEFT JOIN bitacora_operativa_asignaciones a ON a.solicitud_id=s.id
            WHERE s.estatus_solicitud='APROBADA'
              AND s.fecha_evento>=:desde AND s.fecha_evento<:hasta
              AND (a.solicitud_id IS NULL OR a.supervisor_usuario_id=:actor)
            ORDER BY s.fecha_evento,s.hora_inicio,s.id
            """).setParameter("desde",java.sql.Date.valueOf(periodo.atDay(1)))
            .setParameter("hasta",java.sql.Date.valueOf(periodo.plusMonths(1).atDay(1)))
            .setParameter("actor",actor).setMaxResults(201).getResultList();
        if(filas.size()>200)throw new ResponseStatusException(HttpStatus.CONFLICT,"El mes supera 200 reservas; no se muestran resultados parciales");
        List<Candidata> result=filas.stream().map(f->new Candidata(((Number)f[0]).longValue(),
            texto(f[1]),texto(f[2]),texto(f[3]),texto(f[4]),texto(f[5]),
            f[6]==null?"DISPONIBLE_PARA_TOMAR":"ASIGNADA_A_MI")).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Mes(mes,result));
    }
    @PostMapping("/{id}/tomar")
    public ResponseEntity<BitacoraAsignacionService.Resultado> tomar(@PathVariable("id")long id,
            @RequestBody BitacoraAsignacionService.Tomar comando,Authentication auth){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.tomar(id,comando,auth));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Fallo> negocio(ResponseStatusException ex){
        return ResponseEntity.status(ex.getStatusCode()).cacheControl(CacheControl.noStore())
            .body(new Fallo(ex.getReason()==null?"Operacion no aplicada":ex.getReason(),false));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class})
    public ResponseEntity<Fallo> entrada(Exception ex){return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new Fallo("Entrada no valida; operacion no aplicada",false));}
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Fallo> denegado(AccessDeniedException ex){return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).body(new Fallo("Acceso no autorizado",false));}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Fallo> incierto(Exception ex){
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(new Fallo("No se pudo confirmar el resultado. Conserva la operacion y reintenta la misma clave.",true));
    }
    private static String texto(Object valor){return valor==null?"":valor.toString();}
}
