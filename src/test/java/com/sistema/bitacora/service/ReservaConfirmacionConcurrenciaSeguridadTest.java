package com.sistema.bitacora.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Seis pruebas REALES y optativas. No utiliza mocks ni cambia produccion.
 * SOLO contra bitacora_pruebas_confirmacion en 127.0.0.1:3308.
 *
 * Las carreras mantienen primero una fila bloqueada mediante una conexion propia.
 * Antes de liberarla deben verse DOS consultas reales de los trabajadores esperando
 * FOR UPDATE en SHOW FULL PROCESSLIST. Se consultan solo procesos de la misma cuenta;
 * no se solicita PROCESS ni ningun privilegio global. Sin esa observacion la prueba falla.
 *
 * Crea exclusivamente datos temporales propios y los retira al finalizar. La limpieza
 * valida todos los propietarios y documentos ANTES de cualquier DELETE. Se prohibe
 * retirar referencias que existian antes o que utiliza una reserva ajena.
 *
 * Las dos ganadoras avanzan DOS posiciones el contador de la COPIA. No se retrocede.
 * Se permiten huecos normales en AUTO_INCREMENT. La original solo se consulta.
 *
 * HTTP utiliza servidor real, cookies, login real y CSRF real. Un RouterFunction de
 * @TestConfiguration expone un token de la sesion SOLAMENTE en este contexto de prueba;
 * no existe un controlador o endpoint adicional en el codigo de produccion.
 *
 * No cubre caida del proceso, commit desconocido ni todos los escenarios posibles.
 * Dejar MariaDB iniciado y detener IntelliJ; no ejecutar con otros escritores activos.
 */
@EnabledIfEnvironmentVariable(named = "BITACORA_EJECUTAR_CONFIRMACION_IT", matches = "SI")
@Execution(ExecutionMode.SAME_THREAD)
@Import(ReservaConfirmacionConcurrenciaSeguridadTest.CsrfProbeConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mariadb://127.0.0.1:3308/bitacora_pruebas_confirmacion?connectionTimeZone=UTC",
        "spring.datasource.username=bitacora_app",
        "spring.datasource.password=${BITACORA_IT_DB_PASSWORD}",
        "spring.datasource.driver-class-name=org.mariadb.jdbc.Driver",
        "spring.flyway.enabled=false",
        "spring.flyway.url=jdbc:mariadb://127.0.0.1:3308/bitacora_pruebas_confirmacion?connectionTimeZone=UTC",
        "spring.flyway.user=bitacora_app",
        "spring.flyway.password=${BITACORA_IT_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.jpa.show-sql=false",
        "spring.jpa.open-in-view=false",
        "bitacora.reservas.confirmacion-habilitada=true",
        "bitacora.documentos.directorio=${user.home}/bitacora-documentos-pruebas-confirmacion",
        "jwt.secret=${BITACORA_IT_JWT_SECRET}",
        "server.address=127.0.0.1"
})
class ReservaConfirmacionConcurrenciaSeguridadTest {
    private static final String COPIA = "bitacora_pruebas_confirmacion";
    private static final String ORIGINAL = "bitacora_espacios";
    private static final String ESTADO_URL = "/admin/reservas/confirmacion/estado";
    private static final String CONFIRMAR_URL = "/admin/reservas/confirmar";
    private static final String PROBE_CSRF_URL = "/_it/csrf";
    private static final Pattern ATRIBUTOS = Pattern.compile(
            "([A-Za-z_:][A-Za-z0-9_:.-]*)\\s*=\\s*([\"'])(.*?)\\2", Pattern.DOTALL);
    private static final Pattern ETIQUETAS = Pattern.compile(
            "<(?:input|meta)\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ReservaConfirmacionService servicio;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("relojReservas") private Clock reloj;
    @Value("${bitacora.documentos.directorio}") private String directorioConfigurado;
    @LocalServerPort private int puertoHttp;

    private final List<Long> departamentosPropios = new ArrayList<>();
    private final Map<Long, String> clavesDepartamentosPropios = new LinkedHashMap<>();
    private final List<Long> usuariosPropios = new ArrayList<>();
    private final List<Long> nombramientosPropios = new ArrayList<>();
    private final List<Long> espaciosPropios = new ArrayList<>();
    private final Set<String> clavesPropias = new HashSet<>();
    private Path carpetaPruebas;
    private Path carpetaOriginal;
    private String etiqueta;
    private String claveTemporal;
    private long espacioModelo;
    private byte[] oficioModelo;
    private Foto antes;
    private long incrementoEsperado;
    private boolean trabajadoresTerminados = true;

    @BeforeEach
    void prepararYComprobarEntorno() throws Exception {
        departamentosPropios.clear();
        clavesDepartamentosPropios.clear();
        usuariosPropios.clear();
        nombramientosPropios.clear();
        espaciosPropios.clear();
        clavesPropias.clear();
        SecurityContextHolder.clearContext();
        antes = null;
        incrementoEsperado = 0;
        trabajadoresTerminados = true;
        comprobarBase();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "No envolver esta clase en @Transactional");
        assertTrue(servicio.habilitada(), "Confirmacion deshabilitada en el contexto de prueba");
        assertEquals(1L, numero("SELECT COUNT(*) FROM `" + COPIA
                + "`.flyway_schema_history WHERE version='9' AND success=1"));
        Path hogar = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        carpetaPruebas = hogar.resolve("bitacora-documentos-pruebas-confirmacion");
        carpetaOriginal = hogar.resolve("bitacora-documentos");
        assertEquals(carpetaPruebas,
                Path.of(directorioConfigurado).toAbsolutePath().normalize());
        assertFalse(Files.isSymbolicLink(carpetaPruebas));
        assertTrue(Files.isDirectory(carpetaPruebas, LinkOption.NOFOLLOW_LINKS));
        var referencia = jdbc.queryForList("SELECT espacio_id, ruta_oficio_reservacion FROM `"
                + COPIA + "`.solicitudes_espacio WHERE id=1");
        assertEquals(1, referencia.size(), "Falta la reserva de referencia; no crearla artificialmente");
        espacioModelo = ((Number) referencia.get(0).get("espacio_id")).longValue();
        Path archivo = rutaDocumento((String) referencia.get(0).get("ruta_oficio_reservacion"));
        assertTrue(Files.isRegularFile(archivo, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.size(archivo) > 0 && Files.size(archivo) <= 3_000_000);
        oficioModelo = Files.readAllBytes(archivo);
        etiqueta = "ITCS_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        claveTemporal = UUID.randomUUID().toString();
        antes = fotografiar();
    }

    @AfterEach
    void retirarExclusivamenteDatosPropiosYVerificarConservacion() throws Exception {
        SecurityContextHolder.clearContext();
        if (!trabajadoresTerminados) {
            fail("Quedaron trabajadores activos: no se limpia mientras puedan escribir. "
                    + "Mantener la aplicacion detenida y revisar el fallo.");
        }
        if (antes == null) return;
        List<String> documentosPropios = new ArrayList<>();
        TransactionTemplate transaccion = new TransactionTemplate(transactionManager);
        transaccion.executeWithoutResult(estado -> {
            comprobarBase();
            List<ReservaPropia> plan = new ArrayList<>();
            // Primera fase: validar TODO antes de ejecutar cualquier DELETE.
            for (Long departamentoId : departamentosPropios) {
                comprobarDepartamentoPropio(departamentoId);
                var reservas = jdbc.queryForList("SELECT id, registrado_por_usuario_id, motivo_evento, "
                                + "ruta_oficio_reservacion, ruta_requisicion FROM `" + COPIA
                                + "`.solicitudes_espacio WHERE departamento_solicitante_id=?",
                        departamentoId);
                for (var reserva : reservas) {
                    Long actorId = ((Number) reserva.get("registrado_por_usuario_id")).longValue();
                    String motivo = Objects.toString(reserva.get("motivo_evento"), "");
                    assertTrue(usuariosPropios.contains(actorId) && motivo.startsWith(etiqueta + " "),
                            "Reserva ajena en un departamento temporal: no se ejecutara limpieza");
                    Long solicitudId = ((Number) reserva.get("id")).longValue();
                    var registros = jdbc.queryForList("SELECT clave_confirmacion FROM `" + COPIA
                            + "`.confirmaciones_reserva WHERE solicitud_id=?", solicitudId);
                    assertEquals(1, registros.size(), "Reserva temporal sin metadatos; requiere revision");
                    assertTrue(clavesPropias.contains(Objects.toString(registros.get(0).get("clave_confirmacion"))),
                            "Confirmacion no perteneciente a esta prueba: no se ejecutara limpieza");
                    List<String> documentos = new ArrayList<>();
                    for (String campo : List.of("ruta_oficio_reservacion", "ruta_requisicion")) {
                        Object valor = reserva.get(campo);
                        if (valor != null) documentos.add(valor.toString());
                    }
                    plan.add(new ReservaPropia(solicitudId, departamentoId, List.copyOf(documentos)));
                }
            }
            List<Long> idsPropios = plan.stream().map(ReservaPropia::id).toList();
            for (ReservaPropia reserva : plan) {
                for (String referencia : reserva.documentos()) {
                    validarDocumentoNuevoYExclusivo(referencia, idsPropios);
                    documentosPropios.add(referencia);
                }
            }
            // Segunda fase: solo despues de validar propietarios y referencias de todo el plan.
            for (ReservaPropia reserva : plan) {
                jdbc.update("DELETE FROM `" + COPIA + "`.confirmaciones_reserva WHERE solicitud_id=?", reserva.id());
                jdbc.update("DELETE FROM `" + COPIA + "`.consumo_cupos_reserva WHERE solicitud_id=?", reserva.id());
                jdbc.update("DELETE FROM `" + COPIA + "`.solicitud_recursos WHERE solicitud_id=?", reserva.id());
                jdbc.update("DELETE FROM `" + COPIA + "`.solicitudes_espacio WHERE id=? "
                        + "AND departamento_solicitante_id=?", reserva.id(), reserva.departamentoId());
            }
            for (Long id : nombramientosPropios) {
                jdbc.update("DELETE FROM `" + COPIA + "`.nombramientos_departamento WHERE id=?", id);
            }
            for (Long id : usuariosPropios) {
                jdbc.update("DELETE FROM `" + COPIA + "`.usuario_roles WHERE usuario_id=?", id);
                jdbc.update("DELETE FROM `" + COPIA + "`.usuarios WHERE id=?", id);
            }
            for (Long id : espaciosPropios) jdbc.update("DELETE FROM `" + COPIA + "`.espacios WHERE id=?", id);
            for (Long id : departamentosPropios) jdbc.update("DELETE FROM `" + COPIA + "`.departamentos WHERE id=?", id);
        });
        // El commit SQL ya termino. No se eliminan archivos anteriores o referencias ajenas.
        for (String referencia : new LinkedHashSet<>(documentosPropios)) {
            assertFalse(antes.archivosPruebas().containsKey(referencia), "Nunca eliminar un archivo de la fotografia inicial");
            Path archivo = rutaDocumento(referencia);
            assertFalse(Files.isSymbolicLink(archivo));
            if (Files.exists(archivo, LinkOption.NOFOLLOW_LINKS)) {
                assertTrue(Files.isRegularFile(archivo, LinkOption.NOFOLLOW_LINKS));
                Files.delete(archivo);
            }
        }
        Foto despues = fotografiar();
        assertTrue(antes.tablas().equals(despues.tablas()),
                "Las filas existentes cambiaron o quedaron datos temporales; revisar sin borrar a ciegas");
        assertEquals(antes.contadorOriginal(), despues.contadorOriginal(), "Cambio el contador de la original");
        assertEquals(antes.contadorPruebas() + incrementoEsperado, despues.contadorPruebas(),
                "El contador de la copia no coincide con las confirmaciones nuevas esperadas");
        assertTrue(antes.archivosPruebas().equals(despues.archivosPruebas()),
                "Los archivos de pruebas anteriores cambiaron o quedaron archivos nuevos");
        assertTrue(antes.archivosOriginales().equals(despues.archivosOriginales()), "Los documentos originales cambiaron");
        claveTemporal = null;
        oficioModelo = null;
        antes = null;
    }

    private void validarDocumentoNuevoYExclusivo(String referencia, List<Long> idsPropios) {
        comprobarBase();
        rutaDocumento(referencia);
        assertFalse(antes.archivosPruebas().containsKey(referencia),
                "Una reserva temporal referencia un archivo preexistente: no se borrara nada");
        assertFalse(antes.archivosOriginales().containsKey(referencia),
                "La referencia coincide con un documento original: no se borrara nada");
        assertFalse(idsPropios.isEmpty());
        List<Object> parametros = new ArrayList<>();
        parametros.add(referencia);
        parametros.add(referencia);
        parametros.addAll(idsPropios);
        String marcadores = String.join(",", java.util.Collections.nCopies(idsPropios.size(), "?"));
        assertEquals(0L, numero("SELECT COUNT(*) FROM `" + COPIA + "`.solicitudes_espacio "
                        + "WHERE (ruta_oficio_reservacion=? OR ruta_requisicion=?) AND id NOT IN (" + marcadores + ")",
                parametros.toArray()), "Una reserva ajena de la copia utiliza el documento: no se borrara nada");
        assertEquals(0L, numero("SELECT COUNT(*) FROM `" + ORIGINAL + "`.solicitudes_espacio "
                        + "WHERE ruta_oficio_reservacion=? OR ruta_requisicion=?",
                referencia, referencia), "Una reserva original utiliza la referencia: no se borrara nada");
    }

    @Test
    void dosReservasNuevasDelMismoDepartamentoEnEspaciosDistintosNoExcedenUnCupo() throws Exception {
        Long departamento = crearDepartamento("cuota");
        Cuenta administradorUno = crearCuenta(departamento, "admin1", "ADMINISTRADOR");
        Cuenta administradorDos = crearCuenta(departamento, "admin2", "ADMINISTRADOR");
        Cuenta titular = crearCuenta(departamento, "titular", "SOLICITANTE");
        crearNombramiento(departamento, titular.id(), administradorUno.id());
        Long espacioUno = crearEspacio("cuota1");
        Long espacioDos = crearEspacio("cuota2");
        var entradaUno = entrada(espacioUno, titular.correo(), "cuota1");
        var entradaDos = entrada(espacioDos, titular.correo(), "cuota2");
        incrementoEsperado = 1;
        List<Intento> intentos = correrJuntos(administradorUno, entradaUno, administradorDos, entradaDos,
                "departamentos", departamento);
        comprobarUnaGanadoraYUnConflicto(intentos, "cupo");
        assertEquals(1L, numero("SELECT COUNT(*) FROM `" + COPIA
                + "`.solicitudes_espacio WHERE departamento_solicitante_id=?", departamento));
        comprobarReservaGanadora(intentos);
        assertEquals(antes.contadorPruebas() + 1, contador(COPIA));
    }

    @Test
    void dosDepartamentosConCupoNoPuedenCrearReservasNuevasEnElMismoEspacioYHorario() throws Exception {
        Long departamentoUno = crearDepartamento("espacio1");
        Long departamentoDos = crearDepartamento("espacio2");
        Cuenta administradorUno = crearCuenta(departamentoUno, "admin1", "ADMINISTRADOR");
        Cuenta administradorDos = crearCuenta(departamentoDos, "admin2", "ADMINISTRADOR");
        Cuenta titularUno = crearCuenta(departamentoUno, "titular1", "SOLICITANTE");
        Cuenta titularDos = crearCuenta(departamentoDos, "titular2", "SOLICITANTE");
        crearNombramiento(departamentoUno, titularUno.id(), administradorUno.id());
        crearNombramiento(departamentoDos, titularDos.id(), administradorDos.id());
        Long espacio = crearEspacio("compartido");
        var entradaUno = entrada(espacio, titularUno.correo(), "horario1");
        var entradaDos = entrada(espacio, titularDos.correo(), "horario2");
        incrementoEsperado = 1;
        List<Intento> intentos = correrJuntos(administradorUno, entradaUno, administradorDos, entradaDos,
                "espacios", espacio);
        comprobarUnaGanadoraYUnConflicto(intentos, "horario");
        assertEquals(1L, numero("SELECT COUNT(*) FROM `" + COPIA
                + "`.solicitudes_espacio WHERE espacio_id=?", espacio));
        comprobarReservaGanadora(intentos);
        assertEquals(antes.contadorPruebas() + 1, contador(COPIA));
    }

    @Test
    void unaPeticionHttpSinSesionNoObtieneElEstadoAdministrativo() throws Exception {
        HttpClient cliente = navegador();
        HttpResponse<String> respuesta = enviar(cliente, HttpRequest.newBuilder(url(ESTADO_URL))
                .header("Accept", "application/json").GET().build());
        assertTrue(Set.of(302, 303, 401).contains(respuesta.statusCode()),
                "Una peticion anonima no debe obtener el recurso administrativo");
        HttpResponse<String> probe = enviar(cliente, HttpRequest.newBuilder(url(PROBE_CSRF_URL)).GET().build());
        assertTrue(Set.of(302, 303, 401).contains(probe.statusCode()),
                "La ruta de prueba tambien debe requerir autenticacion");
    }

    @Test
    void solicitanteAutenticadoRecibe403EnGetYPostConCsrfRealValido() throws Exception {
        Long departamento = crearDepartamento("httpSol");
        Cuenta solicitante = crearCuenta(departamento, "solicitante", "SOLICITANTE");
        HttpClient cliente = entrarPorFormulario(solicitante);
        Csrf csrf = csrfDeSesionParaSolicitante(cliente);
        HttpResponse<String> estado = enviar(cliente, HttpRequest.newBuilder(url(ESTADO_URL)).GET().build());
        assertEquals(403, estado.statusCode(), "SOLICITANTE no puede leer el recurso administrativo");
        HttpResponse<String> confirmar = postVacio(cliente, csrf.cabecera(), csrf.token());
        assertEquals(403, confirmar.statusCode(),
                "Incluso con CSRF real de su sesion, SOLICITANTE no puede confirmar reservas administrativas");
    }

    @Test
    void administradorAutenticadoNoPuedeConfirmarSinCsrfNiConUnTokenIncorrecto() throws Exception {
        Long departamento = crearDepartamento("httpCsrf");
        Cuenta administrador = crearCuenta(departamento, "admin", "ADMINISTRADOR");
        HttpClient cliente = entrarPorFormulario(administrador);
        Csrf csrf = csrfAdministrativo(cliente);
        HttpResponse<String> sinToken = postVacio(cliente, null, null);
        HttpResponse<String> tokenIncorrecto = postVacio(cliente, csrf.cabecera(), "token-de-prueba-incorrecto");
        assertEquals(403, sinToken.statusCode(), "POST sin CSRF debe ser rechazado por la cadena real");
        assertEquals(403, tokenIncorrecto.statusCode(), "POST con CSRF incorrecto debe ser rechazado");
    }

    @Test
    void administradorConCsrfValidoAccedeAlEstadoPeroUnMultipartSinDatosRecibe400() throws Exception {
        Long departamento = crearDepartamento("httpAdmin");
        Cuenta administrador = crearCuenta(departamento, "admin", "ADMINISTRADOR");
        HttpClient cliente = entrarPorFormulario(administrador);
        Csrf csrf = csrfAdministrativo(cliente);
        HttpResponse<String> estado = enviar(cliente,
                HttpRequest.newBuilder(url(ESTADO_URL)).header("Accept", "application/json").GET().build());
        assertEquals(200, estado.statusCode());
        assertTrue(Pattern.compile("\"habilitada\"\\s*:\\s*true").matcher(estado.body()).find());
        HttpResponse<String> faltanDatos = postVacio(cliente, csrf.cabecera(), csrf.token());
        assertEquals(400, faltanDatos.statusCode(),
                "Con sesion y CSRF validos debe llegar al controlador y rechazar los campos ausentes");
        assertTrue(faltanDatos.headers().firstValue("content-type").orElse("").contains("json"));
        assertTrue(faltanDatos.body().contains("mensaje"));
        assertFalse(faltanDatos.body().contains("stackTrace"));
    }

    private void comprobarBase() {
        assertEquals(COPIA, jdbc.queryForObject("SELECT DATABASE()", String.class),
                "ABORTADO: la conexion no apunta a la copia autorizada");
        assertEquals(3308L, numero("SELECT @@port"), "ABORTADO: puerto MariaDB inesperado");
    }

    private Long crearDepartamento(String sufijo) {
        comprobarBase();
        String parte = sufijo.replaceAll("[^A-Za-z0-9]", "");
        parte = parte.substring(0, Math.min(3, parte.length()));
        String clave = "IT" + parte + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertTrue(clave.length() <= 20);
        assertFalse(clavesDepartamentosPropios.containsValue(clave));
        long id = insertar("INSERT INTO `" + COPIA + "`.departamentos "
                        + "(nombre_departamento, clave_departamento, activo, cupo_mensual) VALUES (?, ?, 1, 1)",
                etiqueta + " " + sufijo, clave);
        departamentosPropios.add(id);
        clavesDepartamentosPropios.put(id, clave);
        return id;
    }

    private Cuenta crearCuenta(Long departamentoId, String sufijo, String nombreRol) {
        comprobarDepartamentoPropio(departamentoId);
        String correo = etiqueta.toLowerCase(Locale.ROOT) + "." + sufijo.toLowerCase(Locale.ROOT) + "@ejemplo.test";
        long id = insertar("INSERT INTO `" + COPIA + "`.usuarios "
                        + "(nombre_completo, correo_institucional, password_hash, departamento_id, activo, fecha_creacion) "
                        + "VALUES (?, ?, ?, ?, 1, ?)",
                etiqueta + " " + sufijo, correo, passwordEncoder.encode(claveTemporal), departamentoId,
                LocalDateTime.now(reloj));
        usuariosPropios.add(id);
        Long rolId = jdbc.queryForObject("SELECT id FROM `" + COPIA + "`.roles WHERE nombre_rol=?", Long.class, nombreRol);
        assertNotNull(rolId, "No existe el rol necesario; la prueba no creara ni alterara roles");
        jdbc.update("INSERT INTO `" + COPIA + "`.usuario_roles (usuario_id, rol_id) VALUES (?, ?)", id, rolId);
        return new Cuenta(id, correo, nombreRol);
    }

    private void crearNombramiento(Long departamentoId, Long usuarioId, Long administradorId) {
        comprobarDepartamentoPropio(departamentoId);
        assertTrue(usuariosPropios.contains(usuarioId) && usuariosPropios.contains(administradorId));
        long id = insertar("INSERT INTO `" + COPIA + "`.nombramientos_departamento "
                        + "(departamento_id, usuario_id, fecha_inicio, registrado_por_usuario_id) VALUES (?, ?, ?, ?)",
                departamentoId, usuarioId, LocalDateTime.now(reloj).minusDays(1), administradorId);
        nombramientosPropios.add(id);
    }

    private Long crearEspacio(String sufijo) {
        comprobarBase();
        comprobarIndicesUnicosDeEspacios();
        List<String> columnas = jdbc.query("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA=? AND TABLE_NAME='espacios' "
                        + "AND LOWER(EXTRA) NOT LIKE '%auto_increment%' "
                        + "AND COALESCE(GENERATION_EXPRESSION, '')='' ORDER BY ORDINAL_POSITION",
                (rs, fila) -> rs.getString(1), COPIA);
        assertTrue(columnas.containsAll(List.of("nombre_espacio", "estado_actual", "activo", "minutos_limpieza")),
                "El esquema de espacios no coincide con el conocido");
        Map<String, Object> modelo = jdbc.queryForMap("SELECT * FROM `" + COPIA + "`.espacios WHERE id=?", espacioModelo);
        List<String> nombres = new ArrayList<>();
        List<Object> valores = new ArrayList<>();
        for (String columna : columnas) {
            assertTrue(columna.matches("[A-Za-z_][A-Za-z0-9_]*"));
            if (columna.equalsIgnoreCase("id")) continue;
            nombres.add("`" + columna + "`");
            Object valor = modelo.get(columna);
            if (columna.equalsIgnoreCase("nombre_espacio")) valor = etiqueta + " " + sufijo;
            else if (columna.equalsIgnoreCase("estado_actual")) valor = "DISPONIBLE";
            else if (columna.equalsIgnoreCase("activo")) valor = 1;
            else if (columna.equalsIgnoreCase("minutos_limpieza")) valor = 30;
            valores.add(valor);
        }
        String sql = "INSERT INTO `" + COPIA + "`.espacios (" + String.join(",", nombres)
                + ") VALUES (" + String.join(",", java.util.Collections.nCopies(valores.size(), "?")) + ")";
        long id = insertar(sql, valores.toArray());
        espaciosPropios.add(id);
        return id;
    }

    private void comprobarIndicesUnicosDeEspacios() {
        Map<String, Set<String>> indices = new LinkedHashMap<>();
        jdbc.query("SELECT INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA=? AND TABLE_NAME='espacios' AND NON_UNIQUE=0 ORDER BY INDEX_NAME, SEQ_IN_INDEX",
                rs -> {
                    String columna = rs.getString("COLUMN_NAME");
                    assertNotNull(columna, "Indice unico por expresion no reconocido; no se clonara el espacio");
                    indices.computeIfAbsent(rs.getString("INDEX_NAME"), ignorado -> new HashSet<>())
                            .add(columna.toLowerCase(Locale.ROOT));
                }, COPIA);
        for (Set<String> columnas : indices.values()) {
            assertTrue(columnas.contains("id") || columnas.contains("nombre_espacio"),
                    "Hay otra restriccion UNIQUE en espacios; revisar antes de copiar valores de la fila modelo");
        }
    }

    private void comprobarDepartamentoPropio(Long id) {
        comprobarBase();
        String clave = clavesDepartamentosPropios.get(id);
        assertTrue(departamentosPropios.contains(id) && clave != null,
                "Identificador no registrado como propio de la prueba");
        assertEquals(1L, numero("SELECT COUNT(*) FROM `" + COPIA
                        + "`.departamentos WHERE id=? AND nombre_departamento LIKE ? AND clave_departamento=?",
                id, etiqueta + " %", clave), "El departamento temporal no pertenece a esta ejecucion");
    }

    private long insertar(String sql, Object... valores) {
        comprobarBase();
        GeneratedKeyHolder claves = new GeneratedKeyHolder();
        jdbc.update(conexion -> {
            PreparedStatement sentencia = conexion.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < valores.length; i++) sentencia.setObject(i + 1, valores[i]);
            return sentencia;
        }, claves);
        Number id = claves.getKey();
        assertNotNull(id, "No se obtuvo el ID de un dato temporal");
        return id.longValue();
    }

    private ReservaConfirmacionService.EntradaConfirmacion entrada(Long espacioId, String titular, String sufijo) {
        String clave = UUID.randomUUID().toString();
        clavesPropias.add(clave);
        return new ReservaConfirmacionService.EntradaConfirmacion(
                clave, espacioId, titular, LocalDate.now(reloj).plusMonths(4).withDayOfMonth(10),
                LocalTime.of(10, 0), LocalTime.of(12, 0), etiqueta + " " + sufijo,
                "", false, "", List.of(),
                new MockMultipartFile("oficioReservacion", "oficio.pdf", "application/pdf", oficioModelo.clone()), null);
    }

    private List<Intento> correrJuntos(Cuenta administradorUno,
                                       ReservaConfirmacionService.EntradaConfirmacion entradaUno,
                                       Cuenta administradorDos, ReservaConfirmacionService.EntradaConfirmacion entradaDos,
                                       String tablaPuerta, Long idPuerta) throws Exception {
        assertTrue(Set.of("departamentos", "espacios").contains(tablaPuerta));
        assertTrue(tablaPuerta.equals("departamentos")
                ? departamentosPropios.contains(idPuerta) : espaciosPropios.contains(idPuerta));
        comprobarBase();
        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch iniciar = new CountDownLatch(1);
        ExecutorService ejecutor = Executors.newFixedThreadPool(2);
        trabajadoresTerminados = false;
        List<Future<Intento>> pendientes = new ArrayList<>();
        Connection puerta = null;
        try {
            puerta = dataSource.getConnection();
            puerta.setAutoCommit(false);
            try (Statement sentencia = puerta.createStatement(); var fila = sentencia.executeQuery("SELECT DATABASE(), @@port")) {
                assertTrue(fila.next());
                assertEquals(COPIA, fila.getString(1));
                assertEquals(3308L, fila.getLong(2));
            }
            try (PreparedStatement bloqueo = puerta.prepareStatement(
                    "SELECT id FROM `" + COPIA + "`.`" + tablaPuerta + "` WHERE id=? FOR UPDATE")) {
                bloqueo.setLong(1, idPuerta);
                try (var fila = bloqueo.executeQuery()) { assertTrue(fila.next()); }
            }
            List<Cuenta> cuentas = List.of(administradorUno, administradorDos);
            List<ReservaConfirmacionService.EntradaConfirmacion> entradas = List.of(entradaUno, entradaDos);
            for (int i = 0; i < 2; i++) {
                Cuenta cuenta = cuentas.get(i);
                var datos = entradas.get(i);
                pendientes.add(ejecutor.submit(() -> {
                    listos.countDown();
                    if (!iniciar.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("No se sincronizaron los trabajadores");
                    var contexto = SecurityContextHolder.createEmptyContext();
                    contexto.setAuthentication(new UsernamePasswordAuthenticationToken(
                            cuenta.correo(), "no-utilizado", List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))));
                    SecurityContextHolder.setContext(contexto);
                    try {
                        return new Intento(servicio.confirmar(datos), 0, "");
                    } catch (ResponseStatusException ex) {
                        return new Intento(null, ex.getStatusCode().value(), Objects.requireNonNullElse(ex.getReason(), ""));
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            assertTrue(listos.await(15, TimeUnit.SECONDS));
            iniciar.countDown();
            try {
                esperarDosConsultasBloqueadas(tablaPuerta, pendientes);
            } finally {
                // La puerta solo hizo SELECT FOR UPDATE. Rollback libera la fila sin escribir nada.
                puerta.rollback();
                puerta.close();
                puerta = null;
            }
            List<Intento> resultados = new ArrayList<>();
            for (Future<Intento> futuro : pendientes) resultados.add(futuro.get(90, TimeUnit.SECONDS));
            return List.copyOf(resultados);
        } finally {
            iniciar.countDown();
            try {
                if (puerta != null) {
                    try { puerta.rollback(); } finally { puerta.close(); }
                }
            } finally {
                ejecutor.shutdownNow();
                trabajadoresTerminados = ejecutor.awaitTermination(45, TimeUnit.SECONDS);
                assertTrue(trabajadoresTerminados, "Hay trabajadores activos; no iniciar la aplicacion");
            }
        }
    }

    private void esperarDosConsultasBloqueadas(String tabla, List<Future<Intento>> pendientes) throws Exception {
        Pattern patron = Pattern.compile("(?is).*\\b" + Pattern.quote(tabla) + "\\b.*\\bfor\\s+update\\b.*");
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < limite) {
            List<Proceso> procesos = jdbc.query("SHOW FULL PROCESSLIST", (rs, fila) ->
                    new Proceso(rs.getLong("Id"), rs.getString("db"), rs.getString("Info")));
            long consultas = procesos.stream().filter(p -> COPIA.equals(p.base())
                            && p.sql() != null && patron.matcher(p.sql()).matches())
                    .map(Proceso::id).distinct().count();
            if (consultas >= 2) return;
            assertFalse(pendientes.stream().anyMatch(Future::isDone),
                    "Un trabajador termino antes de alcanzar el bloqueo esperado; no se verifico una carrera real");
            Thread.sleep(100);
        }
        fail("No se observaron DOS consultas FOR UPDATE de esta cuenta en la copia. "
                + "No declarar aprobada la concurrencia ni conceder permisos globales; revisar el entorno.");
    }

    private void comprobarUnaGanadoraYUnConflicto(List<Intento> intentos, String motivoEsperado) {
        assertEquals(1L, intentos.stream().filter(i -> i.resultado() != null).count(),
                "Solo una reserva nueva debe poder confirmar");
        List<Intento> rechazados = intentos.stream().filter(i -> i.resultado() == null).toList();
        assertEquals(1, rechazados.size());
        assertEquals(HttpStatus.CONFLICT.value(), rechazados.get(0).estado());
        String motivo = rechazados.get(0).motivo().toLowerCase(Locale.ROOT);
        if (motivoEsperado.equals("cupo")) {
            assertTrue(motivo.contains("cupo"), "La segunda debe rechazarse por cupo, no por otro motivo");
        } else {
            assertTrue(motivo.contains("horario") || motivo.contains("cruza") || motivo.contains("limpieza"),
                    "La segunda debe rechazarse por cruce de horario o limpieza");
        }
    }

    private void comprobarReservaGanadora(List<Intento> intentos) {
        var resultado = intentos.stream().map(Intento::resultado).filter(Objects::nonNull).findFirst().orElseThrow();
        assertEquals("APROBADA", resultado.estatus());
        assertFalse(resultado.repetida(), "Son claves y solicitudes NUEVAS, no reintentos");
        assertEquals(1L, numero("SELECT COUNT(*) FROM `" + COPIA + "`.consumo_cupos_reserva WHERE solicitud_id=?", resultado.solicitudId()));
        assertEquals(1L, numero("SELECT COUNT(*) FROM `" + COPIA + "`.confirmaciones_reserva WHERE solicitud_id=?", resultado.solicitudId()));
        assertEquals(0L, numero("SELECT COUNT(*) FROM `" + COPIA + "`.solicitud_recursos WHERE solicitud_id=?", resultado.solicitudId()));
        String referencia = jdbc.queryForObject("SELECT ruta_oficio_reservacion FROM `" + COPIA
                + "`.solicitudes_espacio WHERE id=?", String.class, resultado.solicitudId());
        Path archivo = rutaDocumento(referencia);
        assertTrue(Files.isRegularFile(archivo, LinkOption.NOFOLLOW_LINKS));
        try { assertEquals(oficioModelo.length, Files.size(archivo)); }
        catch (IOException ex) { throw new IllegalStateException("No se pudo verificar el PDF temporal", ex); }
    }

    private HttpClient navegador() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15)).build();
    }

    private HttpClient entrarPorFormulario(Cuenta cuenta) throws Exception {
        HttpClient cliente = navegador();
        HttpResponse<String> formulario = enviar(cliente,
                HttpRequest.newBuilder(url("/login")).GET().build());
        assertEquals(200, formulario.statusCode(), "No se pudo abrir el formulario real de login");
        String token = atributoPorNombre(formulario.body(), "_csrf", "value");
        assertFalse(token.isBlank(), "El login debe incluir CSRF");
        String cuerpo = "username=" + codificar(cuenta.correo())
                + "&password=" + codificar(claveTemporal) + "&_csrf=" + codificar(token);
        HttpResponse<String> respuesta = enviar(cliente, HttpRequest.newBuilder(url("/web/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8)).build());
        assertTrue(Set.of(302, 303).contains(respuesta.statusCode()), "El login real no devolvio redireccion");
        String destino = respuesta.headers().firstValue("location").orElse("");
        assertFalse(destino.isBlank() || destino.contains("error"), "El login de la cuenta temporal fue rechazado");
        return cliente;
    }

    private Csrf csrfAdministrativo(HttpClient cliente) throws Exception {
        HttpResponse<String> panel = enviar(cliente,
                HttpRequest.newBuilder(url("/admin/dashboard")).GET().build());
        assertEquals(200, panel.statusCode(), "El ADMINISTRADOR no pudo acceder al panel");
        String token = atributoPorNombre(panel.body(), "_csrf", "content");
        String cabecera = atributoPorNombre(panel.body(), "_csrf_header", "content");
        assertFalse(token.isBlank() || cabecera.isBlank(), "Faltan los metadatos CSRF del panel");
        assertTrue(cabecera.matches("[A-Za-z0-9-]+"));
        return new Csrf(cabecera, token);
    }

    private Csrf csrfDeSesionParaSolicitante(HttpClient cliente) throws Exception {
        HttpResponse<String> respuesta = enviar(cliente,
                HttpRequest.newBuilder(url(PROBE_CSRF_URL)).header("Accept", "application/json").GET().build());
        assertEquals(200, respuesta.statusCode(), "La sesion autenticada no pudo obtener su token real en el probe de prueba");
        String cabecera = textoJson(respuesta.body(), "cabecera");
        String token = textoJson(respuesta.body(), "token");
        assertTrue(cabecera.matches("[A-Za-z0-9-]+"));
        assertFalse(token.isBlank());
        return new Csrf(cabecera, token);
    }

    private String textoJson(String json, String nombre) {
        Matcher coincidencia = Pattern.compile("\"" + Pattern.quote(nombre)
                + "\"\\s*:\\s*\"([^\"\\\\]+)\"").matcher(json);
        if (!coincidencia.find()) throw new IllegalStateException("Falta un dato de seguridad en el probe de prueba");
        return coincidencia.group(1);
    }

    private HttpResponse<String> postVacio(HttpClient cliente, String nombreCabecera, String token) throws Exception {
        String limite = "ITCS" + UUID.randomUUID().toString().replace("-", "");
        HttpRequest.Builder peticion = HttpRequest.newBuilder(url(CONFIRMAR_URL))
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + limite);
        if (nombreCabecera != null) peticion.header(nombreCabecera, token);
        return enviar(cliente, peticion.POST(HttpRequest.BodyPublishers.ofString(
                "--" + limite + "--\r\n", StandardCharsets.UTF_8)).build());
    }

    private HttpResponse<String> enviar(HttpClient cliente, HttpRequest peticion) throws Exception {
        HttpRequest conTiempo = HttpRequest.newBuilder(peticion, (nombre, valor) -> true)
                .timeout(Duration.ofSeconds(45)).build();
        return cliente.send(conTiempo, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI url(String ruta) { return URI.create("http://127.0.0.1:" + puertoHttp + ruta); }

    private String atributoPorNombre(String html, String nombre, String atributo) {
        Matcher etiquetas = ETIQUETAS.matcher(html);
        while (etiquetas.find()) {
            Map<String, String> valores = new HashMap<>();
            Matcher atributos = ATRIBUTOS.matcher(etiquetas.group());
            while (atributos.find()) {
                valores.put(atributos.group(1).toLowerCase(Locale.ROOT), desescapar(atributos.group(3)));
            }
            if (nombre.equals(valores.get("name")) && valores.containsKey(atributo)) return valores.get(atributo);
        }
        throw new IllegalStateException("No se encontro el campo de seguridad esperado en el HTML");
    }

    private String desescapar(String valor) {
        return valor.replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private String codificar(String valor) { return URLEncoder.encode(valor, StandardCharsets.UTF_8); }

    private Foto fotografiar() throws Exception {
        comprobarBase();
        Map<String, String> tablas = new LinkedHashMap<>();
        for (String esquema : List.of(COPIA, ORIGINAL)) {
            List<String> nombres = jdbc.query("SELECT TABLE_NAME FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME",
                    (rs, fila) -> rs.getString(1), esquema);
            for (String tabla : nombres) {
                assertTrue(tabla.matches("[A-Za-z_][A-Za-z0-9_]*"));
                if (tabla.equals("contador_folios_reserva")) continue;
                List<Map<String, Object>> filas = jdbc.queryForList("SELECT * FROM `" + esquema + "`.`" + tabla + "`");
                List<String> huellas = new ArrayList<>();
                for (Map<String, Object> fila : filas) huellas.add(huellaFila(fila));
                huellas.sort(Comparator.naturalOrder());
                tablas.put(esquema + "." + tabla, huella(String.join("\n", huellas).getBytes(StandardCharsets.UTF_8)));
            }
        }
        return new Foto(Map.copyOf(tablas), contador(COPIA), contador(ORIGINAL),
                fotografiarArchivos(carpetaPruebas), fotografiarArchivos(carpetaOriginal));
    }

    private String huellaFila(Map<String, Object> fila) throws Exception {
        List<String> columnas = new ArrayList<>(fila.keySet());
        columnas.sort(String::compareTo);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream salida = new DataOutputStream(bytes)) {
            for (String columna : columnas) {
                escribir(salida, columna);
                Object valor = fila.get(columna);
                salida.writeBoolean(valor != null);
                if (valor != null) {
                    String texto = valor instanceof byte[] contenido
                            ? Base64.getEncoder().encodeToString(contenido) : valor.toString();
                    escribir(salida, texto);
                }
            }
        }
        return huella(bytes.toByteArray());
    }

    private void escribir(DataOutputStream salida, String valor) throws IOException {
        byte[] bytes = valor.getBytes(StandardCharsets.UTF_8);
        salida.writeInt(bytes.length);
        salida.write(bytes);
    }

    private Map<String, String> fotografiarArchivos(Path carpeta) throws Exception {
        if (!Files.exists(carpeta, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        assertFalse(Files.isSymbolicLink(carpeta));
        assertTrue(Files.isDirectory(carpeta, LinkOption.NOFOLLOW_LINKS));
        Map<String, String> huellas = new LinkedHashMap<>();
        try (var archivos = Files.list(carpeta)) {
            for (Path archivo : archivos.sorted().toList()) {
                assertFalse(Files.isSymbolicLink(archivo));
                assertTrue(Files.isRegularFile(archivo, LinkOption.NOFOLLOW_LINKS));
                huellas.put(archivo.getFileName().toString(), huella(Files.readAllBytes(archivo)));
            }
        }
        return Map.copyOf(huellas);
    }

    private Path rutaDocumento(String referencia) {
        assertNotNull(referencia);
        assertTrue(referencia.matches("[a-fA-F0-9-]{36}\\.pdf"), "Referencia de documento no permitida");
        Path ruta = carpetaPruebas.resolve(referencia).normalize();
        assertEquals(carpetaPruebas, ruta.getParent());
        assertFalse(Files.isSymbolicLink(ruta));
        return ruta;
    }

    private String huella(byte[] contenido) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido));
    }

    private long contador(String esquema) {
        assertTrue(Set.of(COPIA, ORIGINAL).contains(esquema));
        return numero("SELECT ultimo_consecutivo FROM `" + esquema + "`.contador_folios_reserva WHERE id=1");
    }

    private long numero(String sql, Object... argumentos) {
        Long valor = jdbc.queryForObject(sql, Long.class, argumentos);
        if (valor == null) throw new IllegalStateException("Una comprobacion SQL devolvio NULL");
        return valor;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CsrfProbeConfiguration {
        @Bean
        RouterFunction<ServerResponse> csrfProbeSoloEnEsteContexto() {
            return RouterFunctions.route(RequestPredicates.GET(PROBE_CSRF_URL), solicitud -> {
                Object atributo = solicitud.servletRequest().getAttribute(CsrfToken.class.getName());
                if (!(atributo instanceof CsrfToken csrf)) {
                    return ServerResponse.status(500).body(Map.of("mensaje", "No se obtuvo el token de la sesion de prueba"));
                }
                // getToken resuelve el token diferido real del filtro, incluidas sus protecciones XOR.
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("cabecera", csrf.getHeaderName(), "token", csrf.getToken()));
            });
        }
    }

    private record Cuenta(Long id, String correo, String rol) { }
    private record Intento(ReservaConfirmacionService.ResultadoConfirmacion resultado, int estado, String motivo) { }
    private record Csrf(String cabecera, String token) { }
    private record Proceso(long id, String base, String sql) { }
    private record ReservaPropia(Long id, Long departamentoId, List<String> documentos) { }
    private record Foto(Map<String, String> tablas, long contadorPruebas, long contadorOriginal,
                        Map<String, String> archivosPruebas, Map<String, String> archivosOriginales) { }
}
