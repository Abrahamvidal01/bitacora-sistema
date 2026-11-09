package com.sistema.bitacora.service;

import com.sistema.bitacora.entity.Departamento;
import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.EstadoBienInventario;
import com.sistema.bitacora.entity.InventarioEspacio;
import com.sistema.bitacora.entity.NombramientoDepartamento;
import com.sistema.bitacora.entity.SolicitudEspacio;
import com.sistema.bitacora.entity.SolicitudRecurso;
import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Orquestación de confirmación administrativa. No se usa en la comprobación parcial.
 *
 * Requiere V9 y habilitación explícita. El valor predeterminado es deshabilitado.
 * Una transacción nueva READ_COMMITTED evita reutilizar una instantánea antigua
 * después de esperar bloqueos. Todas las escrituras de reserva de este flujo
 * deben pasar por este servicio y respetar el mismo orden de bloqueos.
 *
 * Los callbacks existentes eliminan documentos nuevos cuando se conoce un rollback.
 * No hacen atómico el disco con MariaDB ante caída del proceso o resultado desconocido.
 * Esos casos necesitan conciliación y respaldos; nunca se borran documentos anteriores.
 *
 * Las pruebas unitarias no sustituyen pruebas reales de concurrencia, commit y rollback.
 */
@Service
public class ReservaConfirmacionService {

    private static final int MAX_BYTES = 3_000_000;
    private final EntityManager em;
    private final UsuarioRepository usuarios;
    private final ReservaValidacionService validacion;
    private final ReservaBloqueoService bloqueos;
    private final ReservaCupoValidacionService cupos;
    private final ReservaFormularioValidacionService formulario;
    private final ReservaPdfValidacionService pdf;
    private final ReservaDocumentoAlmacenamientoService documentos;
    private final FolioReservaService folios;
    private final boolean confirmacionHabilitada;

    public ReservaConfirmacionService(
            EntityManager em,
            UsuarioRepository usuarios,
            ReservaValidacionService validacion,
            ReservaBloqueoService bloqueos,
            ReservaCupoValidacionService cupos,
            ReservaFormularioValidacionService formulario,
            ReservaPdfValidacionService pdf,
            ReservaDocumentoAlmacenamientoService documentos,
            FolioReservaService folios,
            @Value("${bitacora.reservas.confirmacion-habilitada:false}")
            boolean confirmacionHabilitada) {
        this.em = em;
        this.usuarios = usuarios;
        this.validacion = validacion;
        this.bloqueos = bloqueos;
        this.cupos = cupos;
        this.formulario = formulario;
        this.pdf = pdf;
        this.documentos = documentos;
        this.folios = folios;
        this.confirmacionHabilitada = confirmacionHabilitada;
    }

    public boolean habilitada() {
        return confirmacionHabilitada;
    }

    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class,
            timeout = 30
    )
    public ResultadoConfirmacion confirmar(EntradaConfirmacion entrada) {
        if (!confirmacionHabilitada) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE,
                    "La confirmación todavía no está habilitada en este entorno");
        }
        Usuario actor = obtenerAdministrador();
        EntradaPreparada datos = preparar(entrada);
        String firma = firmar(datos);
        Usuario solicitante = usuarios.findByCorreoInstitucional(datos.correo())
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND,
                        "No existe una cuenta con ese correo"));
        if (solicitante.getDepartamento() == null
                || solicitante.getDepartamento().getId() == null) {
            throw error(HttpStatus.CONFLICT,
                    "El solicitante no tiene un departamento válido");
        }
        Long departamentoId = solicitante.getDepartamento().getId();

        // Antes de modificar entidades: departamento -> espacio -> usuarios por ID.
        var bloqueo = bloqueos.bloquearParaConfirmar(departamentoId, datos.espacioId());
        refrescarUsuariosOrdenados(actor, solicitante);
        comprobarAdministrador(actor);
        if (!Boolean.TRUE.equals(solicitante.getActivo())
                || solicitante.getDepartamento() == null
                || !departamentoId.equals(solicitante.getDepartamento().getId())) {
            throw error(HttpStatus.CONFLICT,
                    "Cambió la cuenta o el departamento del solicitante; vuelve a comprobar los datos");
        }

        // Repetir la misma petición devuelve su reserva, aun si ya consumió el cupo.
        // Se consulta tras serializar por actor y antes de disponibilidad y cupos.
        ResultadoConfirmacion repetida = buscarRepeticion(datos.clave(), firma, actor.getId());
        if (repetida != null) return repetida;

        var resultado = validarDatos(datos);
        comprobarIdentidades(resultado, actor, solicitante, departamentoId);
        NombramientoDepartamento nombramiento = em.find(
                NombramientoDepartamento.class, resultado.nombramientoId());
        if (nombramiento == null) {
            throw error(HttpStatus.CONFLICT, "El nombramiento ya no existe");
        }
        em.refresh(nombramiento, LockModeType.PESSIMISTIC_WRITE);
        var revalidado = validarDatos(datos);
        comprobarIdentidades(revalidado, actor, solicitante, departamentoId);
        if (!resultado.nombramientoId().equals(revalidado.nombramientoId())
                || nombramiento.getFechaFin() != null
                || !departamentoId.equals(nombramiento.getDepartamento().getId())
                || !solicitante.getId().equals(nombramiento.getUsuario().getId())) {
            throw error(HttpStatus.CONFLICT,
                    "Cambió el nombramiento; vuelve a comprobar los datos");
        }

        // Los datos históricos se leen del inventario bloqueado, nunca del navegador.
        List<DetallePreparado> detalles = bloquearInventario(datos.espacioId(), datos.recursos());
        cupos.validar(departamentoId, revalidado.inicio().toLocalDate());

        String referenciaOficio = documentos.guardarEnTransaccion(datos.oficio()).referencia();
        String referenciaRequisicion = datos.requisicion() == null ? null
                : documentos.guardarEnTransaccion(datos.requisicion()).referencia();
        String folio = folios.generarParaReserva();
        boolean requiereRevision = !datos.externos().isBlank() || datos.requisicion() != null;
        String estatus = requiereRevision ? "PENDIENTE" : "APROBADA";

        SolicitudEspacio solicitud = new SolicitudEspacio();
        solicitud.setFolio(folio);
        solicitud.setUsuario(solicitante);
        solicitud.setDepartamentoSolicitante(bloqueo.departamento());
        solicitud.setNombramiento(nombramiento);
        solicitud.setRegistradoPor(actor);
        solicitud.setEspacio(bloqueo.espacio());
        solicitud.setFechaEvento(revalidado.inicio().toLocalDate());
        solicitud.setHoraInicio(revalidado.inicio().toLocalTime());
        solicitud.setHoraFin(revalidado.fin().toLocalTime());
        solicitud.setMotivoEvento(datos.motivo());
        solicitud.setRecursosExternos(datos.externos().isBlank() ? null : datos.externos());
        solicitud.setRutaOficioReservacion(referenciaOficio);
        solicitud.setRutaRequisicion(referenciaRequisicion);
        solicitud.setRutaOficioRequisicion(null);
        solicitud.setEsUrgenteSinOficio(false);
        solicitud.setTipoOrigen("REGISTRO_ADMIN");
        solicitud.setEstatusSolicitud(estatus);
        em.persist(solicitud);
        em.flush();
        if (solicitud.getId() == null) {
            throw new IllegalStateException("No se obtuvo el identificador de la reserva");
        }

        for (DetallePreparado detalle : detalles) {
            SolicitudRecurso recurso = new SolicitudRecurso();
            recurso.setSolicitudId(solicitud.getId());
            recurso.setInventarioId(detalle.inventarioId());
            recurso.setEspacioId(datos.espacioId());
            recurso.setNombreItem(detalle.nombre());
            recurso.setDescripcionItem(detalle.descripcion());
            recurso.setEstadoBien(detalle.estado());
            recurso.setCantidadSolicitada(detalle.cantidad());
            recurso.setExistenciasAlConfirmar(detalle.existencias());
            em.persist(recurso);
        }
        em.flush();

        int consumos = em.createNativeQuery("""
                INSERT INTO consumo_cupos_reserva (solicitud_id)
                VALUES (:solicitudId)
                """)
                .setParameter("solicitudId", solicitud.getId())
                .executeUpdate();
        if (consumos != 1) {
            throw new IllegalStateException("No se pudo registrar el consumo de cupo");
        }
        String modalidad = datos.urgencia()
                ? (requiereRevision ? "URGENTE_REQUISICION" : "URGENTE_INVENTARIO")
                : "NORMAL";
        int confirmaciones = em.createNativeQuery("""
                INSERT INTO confirmaciones_reserva (
                    clave_confirmacion, solicitud_id, registrado_por_usuario_id,
                    firma_solicitud, modalidad, motivo_urgencia
                ) VALUES (
                    :clave, :solicitudId, :actorId, :firma, :modalidad, :motivoUrgencia
                )
                """)
                .setParameter("clave", datos.clave())
                .setParameter("solicitudId", solicitud.getId())
                .setParameter("actorId", actor.getId())
                .setParameter("firma", firma)
                .setParameter("modalidad", modalidad)
                .setParameter("motivoUrgencia", datos.urgencia() ? datos.motivoUrgencia() : null)
                .executeUpdate();
        if (confirmaciones != 1) {
            throw new IllegalStateException("No se pudo registrar la confirmación");
        }
        em.flush();
        return new ResultadoConfirmacion(solicitud.getId(), folio, estatus, false,
                requiereRevision
                        ? "Reserva registrada y pendiente de revisión del supervisor. "
                        + "Adjuntar documentos no equivale a autorizar la requisición."
                        : "Reserva registrada y aprobada. Se guardaron sus documentos y recursos.");
    }

    private EntradaPreparada preparar(EntradaConfirmacion entrada) {
        if (entrada == null) throw error(HttpStatus.BAD_REQUEST, "La solicitud es obligatoria");
        String clave = entrada.claveConfirmacion();
        try {
            if (clave == null || clave.length() != 36
                    || !UUID.fromString(clave).toString().equals(clave)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw error(HttpStatus.BAD_REQUEST, "La clave de confirmación no es un UUID válido");
        }
        if (entrada.espacioId() == null || entrada.espacioId() <= 0) {
            throw error(HttpStatus.BAD_REQUEST, "Selecciona un espacio válido");
        }
        String correo = normalizarTexto(entrada.correoSolicitante());
        if (correo.isBlank() || correo.length() > 100) {
            throw error(HttpStatus.BAD_REQUEST, "Indica el correo del solicitante registrado");
        }
        if (entrada.fechaEvento() == null || entrada.horaInicio() == null
                || entrada.horaFin() == null || entrada.fechaEvento().getYear() < 1000
                || entrada.fechaEvento().getYear() > 9999) {
            throw error(HttpStatus.BAD_REQUEST, "Indica una fecha y horas válidas");
        }
        if (!entrada.horaFin().isAfter(entrada.horaInicio())) {
            throw error(HttpStatus.BAD_REQUEST,
                    "La hora de fin debe ser posterior al inicio y el evento terminar el mismo día");
        }
        String motivo = formulario.validarMotivo(entrada.motivoEvento());
        String externos = normalizarTexto(entrada.recursosExternos());
        String motivoUrgencia = normalizarTexto(entrada.motivoUrgencia());
        if (entrada.esUrgencia() && (motivoUrgencia.isBlank() || motivoUrgencia.length() > 255)) {
            throw error(HttpStatus.BAD_REQUEST, "Indica un motivo de urgencia de hasta 255 caracteres");
        }
        if (!entrada.esUrgencia() && !motivoUrgencia.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST,
                    "Marca la atención por urgencia si indicas un motivo de urgencia");
        }
        if (entrada.recursos() == null) {
            throw error(HttpStatus.BAD_REQUEST, "La selección de recursos no es válida");
        }
        Set<Long> ids = new HashSet<>();
        List<ReservaRecursosValidacionService.SolicitudRecurso> recursos = new ArrayList<>();
        for (var recurso : entrada.recursos()) {
            if (recurso == null || recurso.articuloId() == null || recurso.articuloId() <= 0
                    || recurso.cantidad() == null || recurso.cantidad() <= 0) {
                throw error(HttpStatus.BAD_REQUEST, "Indica artículos y cantidades enteras positivas");
            }
            if (!ids.add(recurso.articuloId())) {
                throw error(HttpStatus.BAD_REQUEST, "El mismo registro de inventario no puede repetirse");
            }
            recursos.add(new ReservaRecursosValidacionService.SolicitudRecurso(
                    recurso.articuloId(), recurso.cantidad()));
        }
        recursos.sort(Comparator.comparing(ReservaRecursosValidacionService.SolicitudRecurso::articuloId));
        MultipartCopia oficio = copiarDocumento(entrada.oficio(), "Oficio de reservación");
        MultipartCopia requisicion = copiarDocumento(entrada.requisicion(), "Requisición");
        formulario.validarDocumentosRequeridos(externos, oficio != null, requisicion != null);
        return new EntradaPreparada(clave, entrada.espacioId(), correo,
                entrada.fechaEvento(), entrada.horaInicio(), entrada.horaFin(),
                motivo, externos, entrada.esUrgencia(), motivoUrgencia,
                List.copyOf(recursos), oficio, requisicion);
    }

    private MultipartCopia copiarDocumento(MultipartFile archivo, String etiqueta) {
        if (archivo == null) return null;
        if (archivo.getSize() > MAX_BYTES) {
            throw error(HttpStatus.CONTENT_TOO_LARGE, etiqueta + ": el máximo es 3 MB");
        }
        byte[] bytes;
        try (InputStream entrada = archivo.getInputStream()) {
            bytes = entrada.readNBytes(MAX_BYTES + 1);
        } catch (IOException ex) {
            throw error(HttpStatus.BAD_REQUEST, etiqueta + ": no se pudo leer el documento");
        }
        if (bytes.length > MAX_BYTES) {
            throw error(HttpStatus.CONTENT_TOO_LARGE, etiqueta + ": el máximo es 3 MB");
        }
        if (bytes.length == 0) {
            throw error(HttpStatus.BAD_REQUEST, etiqueta + ": el archivo está vacío");
        }
        MultipartCopia copia = new MultipartCopia(archivo.getName(),
                archivo.getOriginalFilename(), archivo.getContentType(), bytes);
        try {
            pdf.validar(copia);
        } catch (ResponseStatusException ex) {
            throw new ResponseStatusException(ex.getStatusCode(), etiqueta + ": "
                    + Objects.requireNonNullElse(ex.getReason(), "PDF no válido"));
        }
        return copia;
    }

    private Usuario obtenerAdministrador() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !autenticacion.isAuthenticated()) {
            throw error(HttpStatus.UNAUTHORIZED, "Debes iniciar sesión");
        }
        Usuario actor = usuarios.findByCorreoInstitucional(autenticacion.getName())
                .orElseThrow(() -> error(HttpStatus.FORBIDDEN, "La cuenta autenticada no está registrada"));
        comprobarAdministrador(actor);
        return actor;
    }

    private void comprobarAdministrador(Usuario actor) {
        if (!Boolean.TRUE.equals(actor.getActivo()) || actor.getRoles() == null
                || actor.getRoles().stream().noneMatch(rol -> rol != null
                && "ADMINISTRADOR".equals(rol.getNombreRol()))) {
            throw error(HttpStatus.FORBIDDEN, "La cuenta debe estar activa y ser ADMINISTRADOR");
        }
    }

    private void refrescarUsuariosOrdenados(Usuario actor, Usuario solicitante) {
        List<Usuario> usuariosOrdenados = new ArrayList<>();
        usuariosOrdenados.add(actor);
        if (!actor.getId().equals(solicitante.getId())) usuariosOrdenados.add(solicitante);
        usuariosOrdenados.sort(Comparator.comparing(Usuario::getId));
        for (Usuario usuario : usuariosOrdenados) {
            em.refresh(usuario, LockModeType.PESSIMISTIC_WRITE);
        }
    }

    private ReservaValidacionService.ResultadoValidacionInicial validarDatos(EntradaPreparada datos) {
        if (datos.urgencia()) {
            return validacion.validarUrgenciaParaAdministrador(
                    datos.espacioId(), datos.correo(), datos.fecha(), datos.inicio(), datos.fin(),
                    datos.motivoUrgencia(), datos.externos(), datos.requisicion() != null).validacion();
        }
        return validacion.validarParaAdministrador(
                datos.espacioId(), datos.correo(), datos.fecha(), datos.inicio(), datos.fin());
    }

    private void comprobarIdentidades(ReservaValidacionService.ResultadoValidacionInicial resultado,
                                      Usuario actor, Usuario solicitante, Long departamentoId) {
        if (!actor.getId().equals(resultado.registradoPorUsuarioId())
                || !solicitante.getId().equals(resultado.solicitanteId())
                || !departamentoId.equals(resultado.departamentoId())) {
            throw error(HttpStatus.CONFLICT,
                    "Cambió el titular o departamento; vuelve a comprobar los datos");
        }
    }

    private List<DetallePreparado> bloquearInventario(Long espacioId,
                                                      List<ReservaRecursosValidacionService.SolicitudRecurso> seleccion) {
        List<DetallePreparado> detalles = new ArrayList<>();
        for (var elegido : seleccion) {
            InventarioEspacio articulo = em.find(InventarioEspacio.class, elegido.articuloId());
            if (articulo == null) {
                throw error(HttpStatus.CONFLICT, "Un artículo seleccionado ya no existe");
            }
            em.refresh(articulo, LockModeType.PESSIMISTIC_WRITE);
            if (articulo.getEspacio() == null || !espacioId.equals(articulo.getEspacio().getId())) {
                throw error(HttpStatus.BAD_REQUEST, "Un artículo no pertenece al espacio seleccionado");
            }
            EstadoBienInventario estado;
            try {
                estado = EstadoBienInventario.desdeTexto(articulo.getEstadoBien());
            } catch (IllegalArgumentException ex) {
                throw error(HttpStatus.CONFLICT, "Un artículo tiene un estado desconocido");
            }
            if (!estado.esReservable()) {
                throw error(HttpStatus.CONFLICT, "Un artículo está en estado MALO y no puede reservarse");
            }
            Integer existencias = articulo.getCantidad();
            if (existencias == null || existencias < 0 || elegido.cantidad() > existencias) {
                throw error(HttpStatus.CONFLICT,
                        "Las existencias cambiaron o son insuficientes; vuelve a comprobar el inventario");
            }
            String nombre = articulo.getNombreItem();
            if (nombre == null || nombre.isBlank() || nombre.length() > 150) {
                throw error(HttpStatus.CONFLICT, "Un artículo tiene un nombre inconsistente");
            }
            detalles.add(new DetallePreparado(articulo.getId(), nombre, articulo.getDescripcion(),
                    estado.name(), elegido.cantidad(), existencias));
        }
        return List.copyOf(detalles);
    }

    private ResultadoConfirmacion buscarRepeticion(String clave, String firma, Long actorId) {
        List<?> filas = em.createNativeQuery("""
                SELECT c.registrado_por_usuario_id, c.firma_solicitud,
                       s.id, s.folio, s.estatus_solicitud
                FROM confirmaciones_reserva c
                INNER JOIN solicitudes_espacio s ON s.id = c.solicitud_id
                WHERE c.clave_confirmacion = :clave
                """).setParameter("clave", clave).getResultList();
        if (filas.isEmpty()) return null;
        Object[] fila = (Object[]) filas.get(0);
        if (!actorId.equals(convertirId(fila[0]))) {
            throw error(HttpStatus.FORBIDDEN, "La clave de confirmación pertenece a otra cuenta");
        }
        if (!firma.equals(fila[1])) {
            throw error(HttpStatus.CONFLICT,
                    "La clave ya se utilizó con otros datos; inicia una nueva confirmación");
        }
        return new ResultadoConfirmacion(convertirId(fila[2]), (String) fila[3], (String) fila[4], true,
                "Esta confirmación ya fue registrada. Se devuelve la misma reserva sin duplicar el cupo.");
    }

    private Long convertirId(Object valor) {
        if (!(valor instanceof Number numero)) {
            throw new IllegalStateException("La confirmación contiene un identificador inválido");
        }
        try {
            long id = new BigDecimal(numero.toString()).longValueExact();
            if (id <= 0) throw new ArithmeticException();
            return id;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalStateException("La confirmación contiene un identificador inválido", ex);
        }
    }

    private String firmar(EntradaPreparada datos) {
        try {
            MessageDigest resumen = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream salida = new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), resumen))) {
                salida.writeInt(1);
                salida.writeLong(datos.espacioId());
                escribirTexto(salida, datos.correo());
                escribirTexto(salida, datos.fecha().toString());
                escribirTexto(salida, datos.inicio().toString());
                escribirTexto(salida, datos.fin().toString());
                escribirTexto(salida, datos.motivo());
                escribirTexto(salida, datos.externos());
                salida.writeBoolean(datos.urgencia());
                escribirTexto(salida, datos.motivoUrgencia());
                salida.writeInt(datos.recursos().size());
                for (var recurso : datos.recursos()) {
                    salida.writeLong(recurso.articuloId());
                    salida.writeInt(recurso.cantidad());
                }
                escribirArchivo(salida, datos.oficio());
                escribirArchivo(salida, datos.requisicion());
            }
            return HexFormat.of().formatHex(resumen.digest());
        } catch (NoSuchAlgorithmException | IOException ex) {
            throw new IllegalStateException("No se pudo identificar la confirmación", ex);
        }
    }

    private void escribirTexto(DataOutputStream salida, String texto) throws IOException {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        salida.writeInt(bytes.length);
        salida.write(bytes);
    }

    private void escribirArchivo(DataOutputStream salida, MultipartCopia archivo) throws IOException {
        salida.writeBoolean(archivo != null);
        if (archivo != null) {
            salida.writeInt(archivo.contenido.length);
            salida.write(archivo.contenido);
        }
    }

    private String normalizarTexto(String valor) {
        return valor == null ? "" : valor.strip();
    }

    private ResponseStatusException error(HttpStatus estado, String mensaje) {
        return new ResponseStatusException(estado, mensaje);
    }

    public record EntradaConfirmacion(
            String claveConfirmacion,
            Long espacioId,
            String correoSolicitante,
            LocalDate fechaEvento,
            LocalTime horaInicio,
            LocalTime horaFin,
            String motivoEvento,
            String recursosExternos,
            boolean esUrgencia,
            String motivoUrgencia,
            List<ReservaRecursosValidacionService.SolicitudRecurso> recursos,
            MultipartFile oficio,
            MultipartFile requisicion) {
    }

    public record ResultadoConfirmacion(
            Long solicitudId, String folio, String estatus, boolean repetida, String mensaje) {
    }

    private record EntradaPreparada(String clave, Long espacioId, String correo,
                                    LocalDate fecha, LocalTime inicio, LocalTime fin,
                                    String motivo, String externos, boolean urgencia,
                                    String motivoUrgencia,
                                    List<ReservaRecursosValidacionService.SolicitudRecurso> recursos,
                                    MultipartCopia oficio, MultipartCopia requisicion) {
    }

    private record DetallePreparado(Long inventarioId, String nombre, String descripcion,
                                    String estado, Integer cantidad, Integer existencias) {
    }

    private static final class MultipartCopia implements MultipartFile {
        private final String nombre;
        private final String nombreOriginal;
        private final String tipoContenido;
        private final byte[] contenido;

        private MultipartCopia(String nombre, String nombreOriginal,
                               String tipoContenido, byte[] contenido) {
            this.nombre = nombre;
            this.nombreOriginal = nombreOriginal;
            this.tipoContenido = tipoContenido;
            this.contenido = contenido;
        }

        @Override public String getName() { return nombre; }
        @Override public String getOriginalFilename() { return nombreOriginal; }
        @Override public String getContentType() { return tipoContenido; }
        @Override public boolean isEmpty() { return contenido.length == 0; }
        @Override public long getSize() { return contenido.length; }
        @Override public byte[] getBytes() { return contenido.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(contenido); }
        @Override public void transferTo(File destino) throws IOException {
            transferTo(destino.toPath());
        }
        @Override public void transferTo(Path destino) throws IOException {
            try (InputStream entrada = getInputStream()) {
                Files.copy(entrada, destino, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
