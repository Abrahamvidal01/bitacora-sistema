package com.sistema.bitacora.supervisorbitacora;

import jakarta.persistence.EntityManager;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** BO4: registra preparacion y avisos internos/correo deshabilitado. No realiza entrega fisica ni envia correo. */
@Service
public class BitacoraPreparacionService {
    static final String SQL_ACTOR = """
        SELECT COALESCE(MAX(u.id),0) FROM usuarios u
        JOIN usuario_roles ur ON ur.usuario_id=u.id JOIN roles r ON r.id=ur.rol_id
        WHERE u.correo_institucional=:correo AND u.activo=1 AND r.nombre_rol='SUPERVISOR'
        """;
    static final String SQL_RESERVA = """
        SELECT id,estatus_solicitud,usuario_id,registrado_por_usuario_id,folio
        FROM solicitudes_espacio WHERE id=:id FOR UPDATE
        """;
    static final String SQL_ASIGNACION = """
        SELECT supervisor_usuario_id FROM bitacora_operativa_asignaciones
        WHERE solicitud_id=:id FOR UPDATE
        """;
    static final String SQL_USUARIOS = """
        SELECT id,correo_institucional,activo FROM usuarios
        WHERE id=:actor OR id=:solicitante OR id=:capturista ORDER BY id FOR UPDATE
        """;
    static final String SQL_ROL_VIGENTE = """
        SELECT u.id FROM usuarios u JOIN usuario_roles ur ON ur.usuario_id=u.id
        JOIN roles r ON r.id=ur.rol_id
        WHERE u.id=:actor AND u.correo_institucional=:correo
          AND u.activo=1 AND r.nombre_rol='SUPERVISOR' FOR UPDATE
        """;
    static final String SQL_REPLAY = """
        SELECT id,solicitud_id,actor_usuario_id,firma_payload,version_esperada,revision,
               solicitante_usuario_id,capturista_usuario_id,supervisor_responsable_usuario_id,
               DATE_FORMAT(fecha_registro_utc,'%Y-%m-%dT%H:%i:%s.%f')
        FROM bitacora_operativa_eventos WHERE clave_operacion=:clave
        """;
    static final String SQL_ULTIMO = """
        SELECT revision,solicitante_usuario_id,capturista_usuario_id,supervisor_responsable_usuario_id
        FROM bitacora_operativa_eventos WHERE solicitud_id=:id ORDER BY revision DESC LIMIT 1 FOR UPDATE
        """;
    static final String SQL_EVENTO = """
        INSERT INTO bitacora_operativa_eventos
        (clave_operacion,firma_payload,solicitud_id,actor_usuario_id,solicitante_usuario_id,
         capturista_usuario_id,supervisor_responsable_usuario_id,version_esperada,revision,accion,
         condiciones_espacio,materiales_preparados,recursos_externos_preparados,observaciones,fecha_registro_utc)
        VALUES (:clave,:firma,:solicitud,:actor,:solicitante,:capturista,:responsable,:esperada,:revision,
                'ESPACIO_LISTO',:condiciones,:materiales,:externos,:observaciones,:fecha)
        """;
    static final String SQL_EVENTO_ID = """
        SELECT id FROM bitacora_operativa_eventos WHERE clave_operacion=:clave
        """;
    static final String SQL_AVISO = """
        INSERT INTO bitacora_operativa_avisos
        (evento_id,destinatario_usuario_id,canal,estado,titulo,mensaje,correo_destino,
         identificador_mensaje,fecha_creacion_utc,fecha_lectura_utc,fecha_envio_utc,intentos,codigo_error)
        VALUES (:evento,:destino,:canal,:estado,:titulo,:mensaje,:correo,:mensajeId,:fecha,NULL,NULL,0,NULL)
        """;
    private static final DateTimeFormatter UTC_TEXT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
    private final EntityManager em;
    private final Clock clock;

    public BitacoraPreparacionService(EntityManager em, @Qualifier("relojReservas") Clock clock) {
        this.em=Objects.requireNonNull(em); this.clock=Objects.requireNonNull(clock);
    }
    public record MarcarListo(String clave,long versionEsperada,String condicionesEspacio,
                              String materialesPreparados,String recursosExternosPreparados,String observaciones) {}
    public record Resultado(long eventoId,long solicitudId,long revision,String fechaUtc,boolean repetida) {}
    private record Persona(long id,String correo,boolean activa) {}

    @PreAuthorize("hasRole('SUPERVISOR')")
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED,
                   rollbackFor=Exception.class,timeout=30)
    public Resultado marcarListo(long solicitudId,MarcarListo c,Authentication auth) {
        validarAutenticacion(auth);
        validarComando(solicitudId,c);
        long actor=numero(em.createNativeQuery(SQL_ACTOR).setParameter("correo",auth.getName()).getSingleResult());
        if(actor<=0)throw error(HttpStatus.FORBIDDEN,"Cuenta sin acceso vigente de supervisor");
        String firma=firma(solicitudId,actor,c);
        List<?> reservas=em.createNativeQuery(SQL_RESERVA).setParameter("id",solicitudId).getResultList();
        if(reservas.isEmpty())throw error(HttpStatus.NOT_FOUND,"Reserva no disponible");
        if(reservas.size()!=1)throw new IllegalStateException("Reserva ambigua");
        Object[] reserva=tuple(reservas.getFirst(),5);
        if(numero(reserva[0])!=solicitudId)throw new IllegalStateException("Identidad de reserva inconsistente");
        long solicitante=positivo(reserva[2]),capturista=positivo(reserva[3]);
        List<?> asignaciones=em.createNativeQuery(SQL_ASIGNACION).setParameter("id",solicitudId).getResultList();
        if(asignaciones.size()!=1 || numero(asignaciones.getFirst())!=actor) {
            throw error(HttpStatus.FORBIDDEN,"Solo el supervisor responsable puede registrar la preparacion");
        }
        // Bloquear las personas concretas en orden de ID antes de revalidar el rol del actor.
        List<?> personas=em.createNativeQuery(SQL_USUARIOS).setParameter("actor",actor)
                .setParameter("solicitante",solicitante).setParameter("capturista",capturista).getResultList();
        Map<Long,Persona> actuales=new HashMap<>();
        for(Object value:personas) {
            Object[] p=tuple(value,3); long id=positivo(p[0]);
            if(id!=actor && id!=solicitante && id!=capturista)throw new IllegalStateException("Participante inesperado");
            String correo=p[1] instanceof String s?s:null;
            boolean activa=Boolean.TRUE.equals(p[2]) || p[2] instanceof Number n && n.intValue()==1;
            if(actuales.put(id,new Persona(id,correo,activa))!=null)throw new IllegalStateException("Participante ambiguo");
        }
        Persona responsable=actuales.get(actor);
        if(responsable==null || !responsable.activa())throw error(HttpStatus.FORBIDDEN,"Cuenta sin acceso vigente de supervisor");
        List<?> roles=em.createNativeQuery(SQL_ROL_VIGENTE).setParameter("actor",actor)
                .setParameter("correo",auth.getName()).getResultList();
        if(roles.size()!=1 || numero(roles.getFirst())!=actor)throw error(HttpStatus.FORBIDDEN,"Cuenta sin acceso vigente de supervisor");
        // El replay devuelve el resultado historico, no intenta recrear avisos con correos actuales.
        // Requiere todavia actor activo y asignacion vigente. No crea nada si la reserva cambio despues.
        List<?> repetidas=em.createNativeQuery(SQL_REPLAY).setParameter("clave",c.clave()).getResultList();
        if(!repetidas.isEmpty()) {
            if(repetidas.size()!=1)throw new IllegalStateException("Clave ambigua");
            Object[] f=tuple(repetidas.getFirst(),10);
            if(numero(f[1])!=solicitudId || numero(f[2])!=actor || !firma.equals(f[3]) ||
               numero(f[4])!=c.versionEsperada() || numero(f[5])!=Math.addExact(c.versionEsperada(),1) || numero(f[8])!=actor) {
                throw error(HttpStatus.CONFLICT,"La clave ya se utilizo con otros datos o cuenta");
            }
            if(!(f[9] instanceof String fecha) || !fecha.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}")) {
                throw new IllegalStateException("Fecha historica no disponible");
            }
            return new Resultado(positivo(f[0]),solicitudId,numero(f[5]),fecha+"Z",true);
        }
        if(!"APROBADA".equals(reserva[1]))throw error(HttpStatus.CONFLICT,"Solo puede prepararse una reserva aprobada");
        List<?> ultimas=em.createNativeQuery(SQL_ULTIMO).setParameter("id",solicitudId).getResultList();
        long revision=0;
        if(!ultimas.isEmpty()) {
            if(ultimas.size()!=1)throw new IllegalStateException("Revision ambigua");
            Object[] anterior=tuple(ultimas.getFirst(),4); revision=positivo(anterior[0]);
            if(numero(anterior[1])!=solicitante || numero(anterior[2])!=capturista || numero(anterior[3])!=actor) {
                throw error(HttpStatus.CONFLICT,"Cambiaron participantes historicos; se requiere revision por un flujo separado");
            }
        }
        if(c.versionEsperada()!=revision)throw error(HttpStatus.CONFLICT,"La preparacion cambio; consulta su revision antes de otra operacion");
        LinkedHashSet<Long> destinatarios=new LinkedHashSet<>(); destinatarios.add(solicitante); destinatarios.add(capturista);
        // Deduplicar por ID, nunca por rol ni correo. Ser autor no agrega un destinatario.
        for(long id:destinatarios) {
            Persona p=actuales.get(id);
            if(p==null || !correoValido(p.correo()))throw error(HttpStatus.CONFLICT,"Falta un correo valido de un participante; no se registraron evento ni avisos");
        }
        String folio=reserva[4] instanceof String s?s:null;
        if(folio==null || folio.isBlank() || !textoValido(folio,100))throw error(HttpStatus.CONFLICT,"El folio requiere revision antes de generar los avisos");
        String titulo="Espacio listo: preparacion operativa";
        String mensaje="La preparacion de la reserva "+folio+" se marco como ESPACIO_LISTO. Esto no acredita entrega ni recepcion fisica. Consulta el detalle operativo con tu cuenta autorizada.";
        LocalDateTime ahora=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        long siguiente=Math.addExact(revision,1);
        int escritos=em.createNativeQuery(SQL_EVENTO).setParameter("clave",c.clave()).setParameter("firma",firma)
                .setParameter("solicitud",solicitudId).setParameter("actor",actor).setParameter("solicitante",solicitante)
                .setParameter("capturista",capturista).setParameter("responsable",actor).setParameter("esperada",c.versionEsperada())
                .setParameter("revision",siguiente).setParameter("condiciones",c.condicionesEspacio())
                .setParameter("materiales",c.materialesPreparados()).setParameter("externos",c.recursosExternosPreparados())
                .setParameter("observaciones",c.observaciones()).setParameter("fecha",ahora).executeUpdate();
        if(escritos!=1)throw new IllegalStateException("No se confirmo un unico evento de preparacion");
        long evento=positivo(em.createNativeQuery(SQL_EVENTO_ID).setParameter("clave",c.clave()).getSingleResult());
        for(long id:destinatarios) {
            aviso(evento,id,"INTERNO","DISPONIBLE",titulo,mensaje,null,null,ahora);
            aviso(evento,id,"CORREO","DESHABILITADO",titulo,mensaje,actuales.get(id).correo(),UUID.randomUUID().toString(),ahora);
        }
        em.flush();
        return new Resultado(evento,solicitudId,siguiente,UTC_TEXT.format(ahora),false);
    }
    private void aviso(long evento,long destino,String canal,String estado,String titulo,String mensaje,
                       String correo,String mensajeId,LocalDateTime fecha) {
        int n=em.createNativeQuery(SQL_AVISO).setParameter("evento",evento).setParameter("destino",destino)
                .setParameter("canal",canal).setParameter("estado",estado).setParameter("titulo",titulo)
                .setParameter("mensaje",mensaje).setParameter("correo",correo).setParameter("mensajeId",mensajeId)
                .setParameter("fecha",fecha).executeUpdate();
        if(n!=1)throw new IllegalStateException("No se confirmo un unico aviso de preparacion");
    }
    private static void validarAutenticacion(Authentication a) {
        if(a==null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken || a.getName()==null || a.getName().isBlank() ||
           a.getAuthorities().stream().noneMatch(x->"ROLE_SUPERVISOR".equals(x.getAuthority()))) {
            throw error(HttpStatus.FORBIDDEN,"Acceso no autorizado");
        }
    }
    private static void validarComando(long id,MarcarListo c) {
        if(id<=0 || c==null || c.clave()==null || !c.clave().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ||
           c.versionEsperada()<0 || c.versionEsperada()==Long.MAX_VALUE ||
           !requerido(c.condicionesEspacio()) || !requerido(c.materialesPreparados()) || !requerido(c.recursosExternosPreparados()) ||
           c.observaciones()!=null && !textoValido(c.observaciones(),4000)) {
            throw error(HttpStatus.BAD_REQUEST,"Clave, revision o textos de preparacion no validos; maximo 4000 caracteres por campo");
        }
    }
    private static boolean requerido(String s) { return s!=null && !s.isBlank() && textoValido(s,4000); }
    private static boolean textoValido(String s,int max) {
        if(s.codePointCount(0,s.length())>max)return false;
        for(int i=0;i<s.length();i++) {
            char ch=s.charAt(i);
            if(Character.isHighSurrogate(ch)) { if(i+1>=s.length() || !Character.isLowSurrogate(s.charAt(++i)))return false; }
            else if(Character.isLowSurrogate(ch) || Character.isISOControl(ch) && ch!='\n' && ch!='\r' && ch!='\t')return false;
        }
        return true;
    }
    static boolean correoValido(String correo) {
        if(correo==null || correo.length()>254 || !correo.equals(correo.trim()) || !correo.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+"))return false;
        int at=correo.indexOf('@'); String local=correo.substring(0,at),domain=correo.substring(at+1);
        if(local.length()>64 || local.startsWith(".") || local.endsWith(".") || local.contains("..") || domain.length()>253 || !domain.contains("."))return false;
        for(String label:domain.split("\\.",-1))if(label.isEmpty() || label.length()>63 || label.startsWith("-") || label.endsWith("-"))return false;
        return true; // Validacion sintactica conservadora; no comprueba DNS ni entrega.
    }
    static String firma(long solicitud,long actor,MarcarListo c) {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            Object[] parts={"BO4:ESPACIO_LISTO:v1",solicitud,actor,c.clave(),c.versionEsperada(),c.condicionesEspacio(),c.materialesPreparados(),c.recursosExternosPreparados(),c.observaciones()};
            for(Object part:parts) {
                if(part==null) { md.update((byte)0); continue; }
                md.update((byte)1); byte[] b=part.toString().getBytes(StandardCharsets.UTF_8);
                md.update(ByteBuffer.allocate(4).putInt(b.length).array()); md.update(b);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 no disponible"); }
    }
    private static Object[] tuple(Object value,int size) { if(!(value instanceof Object[] a) || a.length!=size)throw new IllegalStateException("Resultado de consulta inconsistente"); return a; }
    private static long numero(Object value) { if(!(value instanceof Number n))throw new IllegalStateException("Identificador numerico no disponible"); return n.longValue(); }
    private static long positivo(Object value) { long n=numero(value); if(n<=0)throw new IllegalStateException("Identificador positivo no disponible"); return n; }
    private static ResponseStatusException error(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
