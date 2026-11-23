package com.sistema.bitacora.supervisorbitacora;

import jakarta.persistence.EntityManager;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** BO4 web: lectura privada del responsable y delegacion al servicio transaccional ya instalado. */
@RestController
@RequestMapping("/supervisor/api/bitacora-preparacion")
@PreAuthorize("hasRole('SUPERVISOR')")
public class BitacoraPreparacionController {
    static final String SQL_ACTOR = """
        SELECT COALESCE(MAX(u.id),0) FROM usuarios u
        JOIN usuario_roles ur ON ur.usuario_id=u.id JOIN roles r ON r.id=ur.rol_id
        WHERE u.correo_institucional=:correo AND u.activo=1 AND r.nombre_rol='SUPERVISOR'
        """;
    // El ambito se incluye en CADA proyeccion: no cargar datos primero y autorizar despues.
    static final String SCOPE = """
        JOIN bitacora_operativa_asignaciones a ON a.solicitud_id=s.id
        JOIN usuarios u ON u.id=a.supervisor_usuario_id
        WHERE s.id=:id AND a.supervisor_usuario_id=:actor
          AND u.correo_institucional=:correo AND u.activo=1
          AND EXISTS (SELECT 1 FROM usuario_roles ur JOIN roles r ON r.id=ur.rol_id
                      WHERE ur.usuario_id=u.id AND r.nombre_rol='SUPERVISOR')
        """;
    static final String SQL_CONTEXTO = """
        SELECT s.id,s.folio,s.estatus_solicitud,DATE_FORMAT(s.fecha_evento,'%Y-%m-%d'),
               TIME_FORMAT(s.hora_inicio,'%H:%i'),TIME_FORMAT(s.hora_fin,'%H:%i'),
               e.nombre_espacio,s.motivo_evento,s.recursos_externos
        FROM solicitudes_espacio s JOIN espacios e ON e.id=s.espacio_id
        """ + SCOPE;
    static final String SQL_RECURSOS = """
        SELECT d.inventario_id,d.nombre_item,d.estado_bien,d.cantidad_solicitada
        FROM solicitud_recursos d JOIN solicitudes_espacio s ON s.id=d.solicitud_id
        """ + SCOPE + " ORDER BY d.inventario_id";
    static final String SQL_ULTIMA = """
        SELECT v.id,v.revision,v.condiciones_espacio,v.materiales_preparados,
               v.recursos_externos_preparados,v.observaciones,
               DATE_FORMAT(v.fecha_registro_utc,'%Y-%m-%dT%H:%i:%s.%f')
        FROM bitacora_operativa_eventos v JOIN solicitudes_espacio s ON s.id=v.solicitud_id
        """ + SCOPE + " ORDER BY v.revision DESC";
    static final String SQL_ACCESO = "SELECT COUNT(*) FROM solicitudes_espacio s " + SCOPE;
    private static final Set<String> CAMPOS = Set.of("clave","versionEsperada","condicionesEspacio",
        "materialesPreparados","recursosExternosPreparados","observaciones");
    private final EntityManager em;
    private final BitacoraPreparacionService service;
    public BitacoraPreparacionController(EntityManager em,BitacoraPreparacionService service) {
        this.em=em; this.service=service;
    }
    public record Recurso(long inventarioId,String nombre,String estado,int cantidad) {}
    public record Ultima(long eventoId,long revision,String condicionesEspacio,String materialesPreparados,
                         String recursosExternosPreparados,String observaciones,String fechaUtc) {}
    public record Contexto(long solicitudId,String folio,String estado,String fecha,String inicio,String fin,
                           String espacio,String motivoEvento,String recursosExternosSolicitados,
                           long versionEsperada,boolean editable,List<Recurso> recursos,Ultima ultima) {}
    public record Token(String headerName,String token) {}
    public record Fallo(String mensaje,boolean resultadoIncierto) {}

    @GetMapping("/csrf")
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED,timeout=20)
    public ResponseEntity<Token> csrf(Authentication auth,CsrfToken token) {
        actor(auth);
        if(token==null)throw new IllegalStateException("CSRF no disponible");
        return ok(new Token(token.getHeaderName(),token.getToken()));
    }
    @GetMapping("/{id}")
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED,timeout=20)
    public ResponseEntity<Contexto> contexto(@PathVariable("id") long id,Authentication auth) {
        long actor=actor(auth);
        if(id<=0)throw error(HttpStatus.BAD_REQUEST,"Solicitud no valida");
        List<?> rows=consulta(SQL_CONTEXTO,id,actor,auth).setMaxResults(2).getResultList();
        if(rows.isEmpty())throw noDisponible();
        if(rows.size()!=1)throw new IllegalStateException("Contexto ambiguo");
        Object[] f=tuple(rows.getFirst(),9);
        if(positivo(f[0])!=id)throw new IllegalStateException("Identidad inconsistente");
        List<?> material=consulta(SQL_RECURSOS,id,actor,auth).setMaxResults(201).getResultList();
        if(material.size()>200)throw error(HttpStatus.CONFLICT,"La reserva supera 200 recursos; no se devuelve un detalle parcial");
        List<Recurso> recursos=material.stream().map(value->{
            Object[] r=tuple(value,4); long cantidad=positivo(r[3]);
            if(cantidad>Integer.MAX_VALUE)throw new IllegalStateException("Cantidad no representable");
            return new Recurso(positivo(r[0]),texto(r[1]),texto(r[2]),(int)cantidad);
        }).toList();
        // Solo ultima revision. No se presenta este resultado como historial completo.
        List<?> previous=consulta(SQL_ULTIMA,id,actor,auth).setMaxResults(1).getResultList();
        Ultima ultima=null; long revision=0;
        if(!previous.isEmpty()) {
            Object[] p=tuple(previous.getFirst(),7); revision=positivo(p[1]);
            String fecha=texto(p[6]);
            if(!fecha.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}"))throw new IllegalStateException("Fecha no disponible");
            ultima=new Ultima(positivo(p[0]),revision,texto(p[2]),texto(p[3]),texto(p[4]),p[5]==null?null:texto(p[5]),fecha+"Z");
        }
        // Revalidar tambien al terminar: una baja/reasignacion observada impide devolver la proyeccion.
        if(numero(consulta(SQL_ACCESO,id,actor,auth).getSingleResult())!=1)throw noDisponible();
        String estado=texto(f[2]);
        return ok(new Contexto(id,texto(f[1]),estado,texto(f[3]),texto(f[4]),texto(f[5]),texto(f[6]),
            texto(f[7]),texto(f[8]),revision,"APROBADA".equals(estado),List.copyOf(recursos),ultima));
    }
    @PostMapping("/{id}/listo")
    public ResponseEntity<BitacoraPreparacionService.Resultado> listo(@PathVariable("id") long id,
            @RequestBody Map<String,Object> body,Authentication auth) {
        autenticado(auth);
        // Lista cerrada: se rechazan IDs de actor/destinatarios y cualquier propiedad extra.
        if(body==null || !body.keySet().equals(CAMPOS))throw entradaInvalida();
        Object v=body.get("versionEsperada"); long version;
        if(v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long)version=((Number)v).longValue();
        else if(v instanceof BigInteger n && n.signum()>=0 && n.bitLength()<=63)version=n.longValue();
        else throw entradaInvalida();
        if(!(body.get("clave") instanceof String clave) || !(body.get("condicionesEspacio") instanceof String condiciones) ||
           !(body.get("materialesPreparados") instanceof String materiales) || !(body.get("recursosExternosPreparados") instanceof String externos) ||
           body.get("observaciones")!=null && !(body.get("observaciones") instanceof String))throw entradaInvalida();
        var command=new BitacoraPreparacionService.MarcarListo(clave,version,condiciones,materiales,externos,(String)body.get("observaciones"));
        // El proxy del servicio termina su transaccion antes de que se pueda construir el 200.
        return ok(service.marcarListo(id,command,auth));
    }
    private jakarta.persistence.Query consulta(String sql,long id,long actor,Authentication auth) {
        return em.createNativeQuery(sql).setParameter("id",id).setParameter("actor",actor).setParameter("correo",auth.getName());
    }
    private long actor(Authentication auth) {
        autenticado(auth);
        long id=numero(em.createNativeQuery(SQL_ACTOR).setParameter("correo",auth.getName()).getSingleResult());
        if(id<=0)throw error(HttpStatus.FORBIDDEN,"Acceso no autorizado");
        return id;
    }
    private static void autenticado(Authentication auth) {
        if(auth==null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken || auth.getName()==null || auth.getName().isBlank() ||
           auth.getAuthorities().stream().noneMatch(x->"ROLE_SUPERVISOR".equals(x.getAuthority())))throw error(HttpStatus.FORBIDDEN,"Acceso no autorizado");
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Fallo> negocio(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).cacheControl(CacheControl.noStore())
            .body(new Fallo(ex.getReason()==null?"Operacion no aplicada":ex.getReason(),false));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Fallo> entrada(Exception ex) { return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new Fallo("Entrada no valida; operacion no aplicada",false)); }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Fallo> denegado(AccessDeniedException ex) { return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).body(new Fallo("Acceso no autorizado",false)); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Fallo> incierto(Exception ex) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(new Fallo("No se pudo confirmar el resultado. Conserva la operacion y reintenta la misma clave.",true));
    }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private static ResponseStatusException noDisponible() { return error(HttpStatus.NOT_FOUND,"Preparacion no disponible para esta cuenta"); }
    private static ResponseStatusException entradaInvalida() { return error(HttpStatus.BAD_REQUEST,"Entrada no valida; utiliza solo los seis campos de preparacion"); }
    private static ResponseStatusException error(HttpStatus status,String text) { return new ResponseStatusException(status,text); }
    private static Object[] tuple(Object value,int size) { if(!(value instanceof Object[] a) || a.length!=size)throw new IllegalStateException("Proyeccion inconsistente"); return a; }
    private static long numero(Object value) { if(!(value instanceof Number n))throw new IllegalStateException("Numero no disponible"); return n.longValue(); }
    private static long positivo(Object value) { long n=numero(value); if(n<=0)throw new IllegalStateException("Identificador no disponible"); return n; }
    private static String texto(Object value) { return value==null?"":value.toString(); }
}
