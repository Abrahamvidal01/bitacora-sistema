package com.sistema.bitacora.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INTEGRACION REAL, exclusivamente con la copia local conocida.
 *
 * No usa mocks, H2, Testcontainers ni una transaccion de prueba envolvente.
 * Se activa SOLO con BITACORA_EJECUTAR_CONFIRMACION_IT=SI.
 * La aplicacion de IntelliJ debe estar detenida y no debe haber otros escritores.
 *
 * Prueba 1: reintentar una confirmacion existente sin duplicar escrituras.
 * Prueba 2: dos reintentos concurrentes de ESA misma confirmacion existente.
 * Prueba 3: fallo SQL controlado al final del guardado, verificando rollback real
 * de reserva/detalles/cupo/contador y retirada de los PDF recien creados.
 *
 * La segunda prueba NO demuestra la carrera entre dos reservas NUEVAS,
 * ni la competencia de departamentos distintos por un espacio/cupo.
 * Tampoco prueba HTTP, CSRF, caidas del proceso ni resultado de commit desconocido.
 *
 * NO borra, reintegra, cancela ni modifica reservas existentes. El unico DDL
 * es un trigger temporal con nombre aleatorio y condicionado a un UUID de prueba.
 * Se elimina exclusivamente ese trigger en finally. Un rollback puede dejar
 * huecos normales en AUTO_INCREMENT; eso no se intenta corregir.
 */
@EnabledIfEnvironmentVariable(named = "BITACORA_EJECUTAR_CONFIRMACION_IT", matches = "SI")
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
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
        "jwt.secret=${BITACORA_IT_JWT_SECRET}"
})
class ReservaConfirmacionIntegracionTest {

    private static final String COPIA = "bitacora_pruebas_confirmacion";
    private static final String ORIGINAL = "bitacora_espacios";
    private static final String MARCA_FALLO = "PRUEBA_ROLLBACK_CONTROLADO";
    private static final List<String> TABLAS_OBSERVADAS = List.of(
            "solicitudes_espacio", "solicitud_recursos", "consumo_cupos_reserva",
            "confirmaciones_reserva", "usuarios", "departamentos", "inventario_espacios",
            "nombramientos_departamento", "flyway_schema_history"
    );

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReservaConfirmacionService servicio;
    @Autowired @Qualifier("relojReservas") private Clock reloj;
    @Value("${bitacora.documentos.directorio}") private String directorioConfigurado;

    private Path carpetaPruebas;
    private Path carpetaOriginal;
    private Plantilla plantilla;

    @BeforeEach
    void comprobarEntornoYCargarReservaExistente() throws Exception {
        SecurityContextHolder.clearContext();
        comprobarBase();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "No ejecutar esta clase dentro de una transaccion de prueba");
        assertTrue(AopUtils.isAopProxy(servicio), "Debe utilizarse el servicio real con sus proxies");
        assertTrue(servicio.habilitada(), "La confirmacion debe estar habilitada solo en este contexto de prueba");
        assertEquals(1L, numero("""
                SELECT COUNT(*) FROM bitacora_pruebas_confirmacion.flyway_schema_history
                WHERE version = '9' AND success = 1
                """), "La copia debe tener V9 aplicada antes de esta prueba");

        Path hogar = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        carpetaPruebas = hogar.resolve("bitacora-documentos-pruebas-confirmacion");
        carpetaOriginal = hogar.resolve("bitacora-documentos");
        assertEquals(carpetaPruebas,
                Path.of(directorioConfigurado).toAbsolutePath().normalize(),
                "El servicio de documentos debe usar exclusivamente la carpeta de pruebas");
        assertNotEquals(carpetaOriginal, carpetaPruebas);
        assertFalse(Files.isSymbolicLink(carpetaPruebas), "No se permite una carpeta de pruebas simbolica");
        assertTrue(Files.isDirectory(carpetaPruebas, LinkOption.NOFOLLOW_LINKS),
                "No existe la carpeta de documentos de pruebas");

        plantilla = jdbc.queryForObject("""
                SELECT s.id, s.folio, s.estatus_solicitud, s.espacio_id,
                       s.departamento_solicitante_id, s.fecha_evento,
                       s.hora_inicio, s.hora_fin, s.motivo_evento, s.recursos_externos,
                       s.ruta_oficio_reservacion, s.ruta_requisicion,
                       c.clave_confirmacion, c.modalidad, c.motivo_urgencia,
                       a.correo_institucional AS correo_actor,
                       u.correo_institucional AS correo_titular
                FROM bitacora_pruebas_confirmacion.solicitudes_espacio s
                INNER JOIN bitacora_pruebas_confirmacion.confirmaciones_reserva c
                    ON c.solicitud_id = s.id
                INNER JOIN bitacora_pruebas_confirmacion.usuarios a
                    ON a.id = c.registrado_por_usuario_id
                INNER JOIN bitacora_pruebas_confirmacion.usuarios u ON u.id = s.usuario_id
                WHERE s.id = 1
                """, (rs, fila) -> new Plantilla(
                rs.getLong("id"), rs.getString("folio"), rs.getString("estatus_solicitud"),
                rs.getLong("espacio_id"), rs.getLong("departamento_solicitante_id"),
                rs.getDate("fecha_evento").toLocalDate(),
                rs.getTime("hora_inicio").toLocalTime(), rs.getTime("hora_fin").toLocalTime(),
                rs.getString("motivo_evento"), texto(rs.getString("recursos_externos")),
                rs.getString("ruta_oficio_reservacion"), rs.getString("ruta_requisicion"),
                rs.getString("clave_confirmacion"), rs.getString("modalidad"),
                texto(rs.getString("motivo_urgencia")),
                rs.getString("correo_actor"), rs.getString("correo_titular")));
        assertNotNull(plantilla, "No existe la reserva de referencia en la copia");
        assertEquals("PENDIENTE", plantilla.estatus(),
                "La referencia esperada es la reserva pendiente ya comprobada");
        assertEquals("URGENTE_REQUISICION", plantilla.modalidad(),
                "La referencia esperada es la urgencia con requisicion ya comprobada");
        assertEquals(4, recursos().size(), "La reserva de referencia debe tener sus cuatro detalles");
        assertTrue(leerPdf(plantilla.oficio()).length > 0);
        assertTrue(leerPdf(plantilla.requisicion()).length > 0);
    }

    @AfterEach
    void limpiarContextoDeSeguridad() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reintentarLaConfirmacionExistenteNoDuplicaReservaCupoFolioNiPdf() throws Exception {
        Estado antes = estado();
        var entrada = entradaExistente();

        var resultado = comoAdministrador(() -> servicio.confirmar(entrada));

        assertTrue(resultado.repetida(), "Debe recuperar la confirmacion existente");
        assertEquals(plantilla.id(), resultado.solicitudId());
        assertEquals(plantilla.folio(), resultado.folio());
        assertEquals(plantilla.estatus(), resultado.estatus());
        assertEquals(antes, estado(), "El reintento no debe modificar filas, contador ni documentos");
    }

    @Test
    void dosReintentosConcurrentesDeLaMismaReservaExistenteNoLaDuplican() throws Exception {
        Estado antes = estado();
        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch iniciar = new CountDownLatch(1);
        ExecutorService trabajadores = Executors.newFixedThreadPool(2);
        List<Future<ReservaConfirmacionService.ResultadoConfirmacion>> resultados = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                var entrada = entradaExistente();
                resultados.add(trabajadores.submit(() -> {
                    listos.countDown();
                    if (!iniciar.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("No se sincronizaron los reintentos");
                    }
                    return comoAdministrador(() -> servicio.confirmar(entrada));
                }));
            }
            assertTrue(listos.await(10, TimeUnit.SECONDS), "No se prepararon los dos trabajadores");
            iniciar.countDown();
            for (var futuro : resultados) {
                var resultado = futuro.get(90, TimeUnit.SECONDS);
                assertTrue(resultado.repetida(), "Ambos deben recuperar la confirmacion existente");
                assertEquals(plantilla.id(), resultado.solicitudId());
                assertEquals(plantilla.folio(), resultado.folio());
            }
        } finally {
            iniciar.countDown();
            trabajadores.shutdownNow();
            assertTrue(trabajadores.awaitTermination(40, TimeUnit.SECONDS),
                    "Quedaron trabajadores activos; no reanudar la aplicacion todavia");
        }
        assertEquals(antes, estado(), "Los reintentos concurrentes no deben crear filas ni PDF");
    }

    @Test
    void falloSqlAlFinalRevierteReservaDetallesCupoContadorYDocumentosNuevos() throws Exception {
        Estado antes = estado();
        LocalDate fechaLibre = buscarFechaFuturaConCupoYEspacio();
        String clave = UUID.randomUUID().toString();
        String trigger = "it_fallo_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(trigger.matches("it_fallo_[a-f0-9]{32}"));
        var base = entradaExistente();
        var entrada = new ReservaConfirmacionService.EntradaConfirmacion(
                clave, base.espacioId(), base.correoSolicitante(), fechaLibre,
                base.horaInicio(), base.horaFin(), "PRUEBA ROLLBACK CONTROLADO - NO ES EVENTO REAL",
                base.recursosExternos(), true, "Verificacion automatica del rollback en la copia",
                base.recursos(), base.oficio(), base.requisicion());
        boolean creado = false;
        try {
            comprobarBase();
            // El trigger falla SOLO para esta clave aleatoria, despues de las escrituras previas.
            // JDBC ejecuta directamente el CREATE: no se utiliza DELIMITER.
            jdbc.execute("CREATE TRIGGER `" + COPIA + "`.`" + trigger + "` "
                    + "BEFORE INSERT ON `" + COPIA + "`.`confirmaciones_reserva` "
                    + "FOR EACH ROW BEGIN "
                    + "IF NEW.clave_confirmacion = '" + clave + "' THEN "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '" + MARCA_FALLO + "'; "
                    + "END IF; END");
            creado = true;

            RuntimeException fallo = assertThrows(RuntimeException.class,
                    () -> comoAdministrador(() -> servicio.confirmar(entrada)));
            assertTrue(contieneMarca(fallo),
                    "La operacion fallo antes del punto controlado: no se puede dar el rollback por verificado");
            assertEquals(0L, numero("""
                    SELECT COUNT(*) FROM bitacora_pruebas_confirmacion.confirmaciones_reserva
                    WHERE clave_confirmacion = ?
                    """, clave));
            assertEquals(0L, numero("""
                    SELECT COUNT(*) FROM bitacora_pruebas_confirmacion.solicitudes_espacio
                    WHERE motivo_evento = 'PRUEBA ROLLBACK CONTROLADO - NO ES EVENTO REAL'
                      AND fecha_evento = ? AND espacio_id = ?
                    """, fechaLibre, plantilla.espacioId()));
            assertEquals(antes, estado(),
                    "El rollback debe conservar filas previas/contador/documentos y retirar los nuevos PDF");
        } finally {
            if (creado) {
                comprobarBase();
                // No se toca ningun otro trigger ni se borran reservas o documentos.
                jdbc.execute("DROP TRIGGER `" + COPIA + "`.`" + trigger + "`");
            }
        }
    }

    private void comprobarBase() {
        assertEquals(COPIA, jdbc.queryForObject("SELECT DATABASE()", String.class),
                "ABORTADO: la conexion no apunta a la copia de pruebas");
        assertEquals(3308L, numero("SELECT @@port"),
                "ABORTADO: el servidor no utiliza el puerto local esperado");
    }

    private ReservaConfirmacionService.EntradaConfirmacion entradaExistente() throws IOException {
        return new ReservaConfirmacionService.EntradaConfirmacion(
                plantilla.clave(), plantilla.espacioId(), plantilla.correoTitular(),
                plantilla.fecha(), plantilla.inicio(), plantilla.fin(), plantilla.motivo(),
                plantilla.externos(), !"NORMAL".equals(plantilla.modalidad()), plantilla.motivoUrgencia(),
                recursos(), new MockMultipartFile("oficioReservacion", "oficio.pdf",
                "application/pdf", leerPdf(plantilla.oficio())),
                plantilla.requisicion() == null ? null : new MockMultipartFile(
                        "requisicion", "requisicion.pdf", "application/pdf", leerPdf(plantilla.requisicion())));
    }

    private List<ReservaRecursosValidacionService.SolicitudRecurso> recursos() {
        return jdbc.query("""
                SELECT inventario_id, cantidad_solicitada
                FROM bitacora_pruebas_confirmacion.solicitud_recursos
                WHERE solicitud_id = ? ORDER BY inventario_id
                """, (rs, fila) -> new ReservaRecursosValidacionService.SolicitudRecurso(
                rs.getLong("inventario_id"), rs.getInt("cantidad_solicitada")), plantilla.id());
    }

    private byte[] leerPdf(String referencia) throws IOException {
        assertNotNull(referencia, "Falta una referencia de PDF en la reserva de prueba");
        assertTrue(referencia.matches("[a-fA-F0-9-]{36}\\.pdf"),
                "Referencia de archivo no permitida para esta prueba");
        Path ruta = carpetaPruebas.resolve(referencia).normalize();
        assertEquals(carpetaPruebas, ruta.getParent());
        assertFalse(Files.isSymbolicLink(ruta), "No se permiten PDF simbolicos");
        assertTrue(Files.isRegularFile(ruta, LinkOption.NOFOLLOW_LINKS),
                "Falta un PDF de referencia en la carpeta de pruebas");
        assertTrue(Files.size(ruta) > 0 && Files.size(ruta) <= 3_000_000,
                "El PDF de referencia no tiene un tamano permitido");
        return Files.readAllBytes(ruta);
    }

    private <T> T comoAdministrador(Supplier<T> operacion) {
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(new UsernamePasswordAuthenticationToken(
                plantilla.correoActor(), "no-utilizado",
                List.of(new SimpleGrantedAuthority("ROLE_ADMINISTRADOR"))));
        SecurityContextHolder.setContext(contexto);
        try {
            return operacion.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private LocalDate buscarFechaFuturaConCupoYEspacio() {
        LocalDate referencia = LocalDate.now(reloj).plusMonths(2).withDayOfMonth(10);
        for (int desplazamiento = 0; desplazamiento < 12; desplazamiento++) {
            LocalDate candidata = referencia.plusMonths(desplazamiento);
            YearMonth mes = YearMonth.from(candidata);
            long consumos = numero("""
                    SELECT COUNT(*)
                    FROM bitacora_pruebas_confirmacion.consumo_cupos_reserva c
                    INNER JOIN bitacora_pruebas_confirmacion.solicitudes_espacio s ON s.id = c.solicitud_id
                    WHERE s.departamento_solicitante_id = ?
                      AND s.fecha_evento BETWEEN ? AND ? AND c.fecha_reintegro IS NULL
                    """, plantilla.departamentoId(), mes.atDay(1), mes.atEndOfMonth());
            if (consumos != 0) continue;
            for (int dia = 10; dia <= Math.min(20, mes.lengthOfMonth()); dia++) {
                LocalDate fecha = mes.atDay(dia);
                long eventos = numero("""
                        SELECT COUNT(*) FROM bitacora_pruebas_confirmacion.solicitudes_espacio
                        WHERE espacio_id = ? AND fecha_evento BETWEEN ? AND ?
                        """, plantilla.espacioId(), fecha.minusDays(1), fecha.plusDays(1));
                if (eventos == 0) return fecha;
            }
        }
        throw new IllegalStateException("No se encontro fecha libre con cupo para el rollback; no se alteraron cupos");
    }

    private Estado estado() throws Exception {
        comprobarBase();
        Map<String, Long> contadores = new LinkedHashMap<>();
        for (String esquema : List.of(COPIA, ORIGINAL)) {
            for (String tabla : TABLAS_OBSERVADAS) {
                contadores.put(esquema + "." + tabla,
                        numero("SELECT COUNT(*) FROM `" + esquema + "`.`" + tabla + "`"));
            }
            contadores.put(esquema + ".ultimo_consecutivo", numero(
                    "SELECT ultimo_consecutivo FROM `" + esquema + "`.contador_folios_reserva WHERE id = 1"));
        }
        return new Estado(Map.copyOf(contadores), fotografiarArchivos(carpetaPruebas),
                fotografiarArchivos(carpetaOriginal));
    }

    private Archivos fotografiarArchivos(Path carpeta) throws Exception {
        if (!Files.exists(carpeta, LinkOption.NOFOLLOW_LINKS)) return new Archivos(false, Map.of());
        assertFalse(Files.isSymbolicLink(carpeta), "No se admiten carpetas de documentos simbolicas");
        assertTrue(Files.isDirectory(carpeta, LinkOption.NOFOLLOW_LINKS));
        Map<String, String> huellas = new LinkedHashMap<>();
        try (var rutas = Files.list(carpeta)) {
            for (Path archivo : rutas.sorted().toList()) {
                assertFalse(Files.isSymbolicLink(archivo), "No se admiten documentos simbolicos");
                assertTrue(Files.isRegularFile(archivo, LinkOption.NOFOLLOW_LINKS),
                        "La carpeta contiene un elemento que esta prueba no puede comprobar");
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archivo));
                huellas.put(archivo.getFileName().toString(), HexFormat.of().formatHex(hash));
            }
        }
        return new Archivos(true, Map.copyOf(huellas));
    }

    private long numero(String sql, Object... argumentos) {
        Long valor = jdbc.queryForObject(sql, Long.class, argumentos);
        if (valor == null) throw new IllegalStateException("Una comprobacion SQL devolvio NULL");
        return valor;
    }

    private boolean contieneMarca(Throwable error) {
        for (Throwable actual = error; actual != null; actual = actual.getCause()) {
            if (actual.getMessage() != null && actual.getMessage().contains(MARCA_FALLO)) return true;
            if (actual.getCause() == actual) break;
        }
        return false;
    }

    private static String texto(String valor) {
        return valor == null ? "" : valor;
    }

    private record Estado(Map<String, Long> contadores, Archivos pruebas, Archivos originales) {
    }
    private record Archivos(boolean existe, Map<String, String> huellas) {
    }
    private record Plantilla(Long id, String folio, String estatus, Long espacioId, Long departamentoId,
                             LocalDate fecha, LocalTime inicio, LocalTime fin, String motivo, String externos,
                             String oficio, String requisicion, String clave, String modalidad,
                             String motivoUrgencia, String correoActor, String correoTitular) {
    }
}