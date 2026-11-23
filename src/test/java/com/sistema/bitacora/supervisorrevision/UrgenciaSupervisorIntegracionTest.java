package com.sistema.bitacora.supervisorrevision;

import com.sistema.bitacora.config.ReservaTiempoConfig;
import com.sistema.bitacora.config.SecurityConfig;
import com.sistema.bitacora.repository.UsuarioRepository;
import com.sistema.bitacora.security.JwtAuthenticationFilter;
import com.sistema.bitacora.security.JwtUtils;
import com.sistema.bitacora.service.UsuarioDetailsService;
import jakarta.persistence.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.io.OutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Integracion MariaDB opt-in. Contexto acotado al modulo y seguridad reales; NO arranque completo ni navegador. */
@EnabledIfEnvironmentVariable(named="BITACORA_SUPERVISOR_IT", matches="SI")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes=UrgenciaSupervisorIntegracionTest.Harness.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers=UrgenciaSupervisorIntegracionTest.SafeInitializer.class)
class UrgenciaSupervisorIntegracionTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private DataSource dataSource;
    @Autowired private UrgenciaRevisionService service;
    @Autowired @Qualifier("relojReservas") private Clock clock;
    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @PersistenceContext private EntityManager em;
    @MockitoSpyBean private UrgenciaAuditWriter audit;
    @Value("${local.server.port}") private int port;
    private SupervisorItSupport.Config config;
    private List<Actor> actors;
    private Actor actor;
    private Historical historical;
    private Fixture fixture;
    private Map<String,byte[]> baseline;

    public static class SafeInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            SupervisorItLogGuard.aplicar(context);
            var cfg = SupervisorItSupport.config(true);
            ConfigurableEnvironment env = context.getEnvironment();
            // Antes de refresh/beans: no ocultar una URL entrante incorrecta.
            SupervisorItSupport.exactUrl("entorno Spring datasource", env.getProperty("spring.datasource.url"));
            SupervisorItSupport.exactUrl("entorno Spring Flyway", env.getProperty("spring.flyway.url"));
            String jwt = SupervisorItSupport.required("BITACORA_SUPERVISOR_JWT");
            SupervisorItSupport.require(jwt.matches("[0-9a-f]{64}"), "JWT temporal no valido; no se mostrara su valor");
            Map<String,Object> safe = new HashMap<>();
            safe.put("spring.datasource.url", SupervisorItSupport.URL);
            safe.put("spring.flyway.url", SupervisorItSupport.URL);
            safe.put("spring.datasource.username", cfg.user);
            safe.put("spring.datasource.password", cfg.password);
            safe.put("spring.datasource.driver-class-name", "org.mariadb.jdbc.Driver");
            safe.put("spring.flyway.enabled", "false");
            safe.put("spring.flyway.baseline-on-migrate", "false");
            safe.put("spring.flyway.clean-disabled", "true");
            safe.put("spring.jpa.hibernate.ddl-auto", "none");
            safe.put("spring.jpa.generate-ddl", "false");
            safe.put("spring.jpa.properties.jakarta.persistence.schema-generation.database.action", "none");
            safe.put("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action", "none");
            safe.put("spring.sql.init.mode", "never");
            safe.put("spring.jpa.show-sql", "false");
            safe.put("spring.jpa.open-in-view", "false");
            safe.put("spring.liquibase.enabled", "false");
            safe.put("spring.batch.jdbc.initialize-schema", "never");
            safe.put("spring.session.jdbc.initialize-schema", "never");
            safe.put("spring.quartz.jdbc.initialize-schema", "never");
            safe.put("spring.main.allow-bean-definition-overriding", "false");
            safe.put("server.address", "127.0.0.1");
            safe.put("server.port", "0");
            safe.put("server.ssl.enabled", "false");
            safe.put("server.servlet.context-path", "");
            safe.put("server.servlet.session.cookie.secure", "false");
            safe.put("server.servlet.session.timeout", "10m");
            safe.put("server.error.include-message", "never");
            safe.put("server.error.include-stacktrace", "never");
            safe.put("bitacora.supervisor.revision-habilitada", "true");
            safe.put("bitacora.reservas.confirmacion-habilitada", "false");
            safe.put("bitacora.documentos.directorio", cfg.documents.toString());
            safe.put("jwt.secret", jwt);
            safe.put("logging.level.root", "WARN");
            safe.put("logging.level.org.hibernate", "ERROR");
            safe.put("logging.level.org.springframework.jdbc", "ERROR");
            safe.put("logging.level.org.mariadb.jdbc", "OFF");
            safe.put("logging.level.org.hibernate.orm.connections.pooling", "OFF");
            safe.put("logging.level.org.springframework.orm.jpa", "OFF");
            env.getPropertySources().addFirst(new MapPropertySource("supervisor-it-seguro", safe));
        }
    }
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses=UsuarioRepository.class)
    @Import({UrgenciaRevisionService.class, UrgenciaRevisionController.class, UrgenciaCupo.class,
            UrgenciaAuditWriter.class, UrgenciaDocumentos.class, SecurityConfig.class,
            JwtAuthenticationFilter.class, JwtUtils.class, UsuarioDetailsService.class, ReservaTiempoConfig.class})
    static class Harness {
        @Bean DataSource dataSource(Environment env) {
            SupervisorItLogGuard.comprobar();
            // Segunda guarda ANTES de construir el objeto DataSource.
            var cfg = SupervisorItSupport.config(true);
            SupervisorItSupport.exactUrl("datasource efectivo", env.getProperty("spring.datasource.url"));
            SupervisorItSupport.exactUrl("Flyway efectivo", env.getProperty("spring.flyway.url"));
            SupervisorItSupport.require("false".equals(env.getProperty("spring.flyway.enabled")), "Flyway debe estar deshabilitado");
            var ds = new DriverManagerDataSource();
            ds.setUrl(SupervisorItSupport.URL);
            ds.setUsername(cfg.user);
            ds.setPassword(cfg.password);
            return ds;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) throws SQLException {
            SupervisorItLogGuard.comprobar();
            try (Connection connection = ds.getConnection()) {
                SupervisorItSupport.verifyServer(connection);
                SupervisorItSupport.verifyHistory(connection, true);
                SupervisorItSupport.verifyTables(connection, true);
            }
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan("com.sistema.bitacora.entity");
            var adapter = new HibernateJpaVendorAdapter();
            adapter.setGenerateDdl(false); adapter.setShowSql(false);
            factory.setJpaVendorAdapter(adapter);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none",
                    "hibernate.dialect", "org.hibernate.community.dialect.MariaDBLegacyDialect",
                    "hibernate.show_sql", "false", "hibernate.jdbc.time_zone", "UTC",
                    "jakarta.persistence.schema-generation.database.action", "none",
                    "jakarta.persistence.schema-generation.scripts.action", "none"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean LoginCsrfOnlyForIt loginCsrfOnlyForIt() { return new LoginCsrfOnlyForIt(); }
    }
    /** Solo obtiene CSRF anonimo: NO autentica ni crea sesiones autenticadas. */
    @RestController static class LoginCsrfOnlyForIt {
        record LoginCsrf(String headerName,String token) { }
        @GetMapping("/login") LoginCsrf login(CsrfToken token) { return new LoginCsrf(token.getHeaderName(), token.getToken()); }
    }
    @BeforeAll void preflightAndBaseline() throws Exception {
        config = SupervisorItSupport.config(true);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(AopUtils.isAopProxy(service), "Se requiere servicio real con interceptores");
        assertFalse(environment.getProperty("spring.flyway.enabled", Boolean.class, true));
        assertEquals("none", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("never", environment.getProperty("spring.sql.init.mode"));
        try (Connection connection = dataSource.getConnection()) {
            SupervisorItSupport.verifyServer(connection);
            SupervisorItSupport.verifyHistory(connection, true);
            SupervisorItSupport.verifyTables(connection, true);
        }
        try (var files = Files.list(config.documents)) {
            assertEquals(List.of(SupervisorItSupport.MARKER), files.map(p -> p.getFileName().toString()).sorted().toList(), "El directorio IT debe contener solo su marca inicial");
        }
        actors = loadActors();
        assertFalse(actors.isEmpty(), "Se requiere cuenta EXISTENTE activa con rol SUPERVISOR; no se creara ninguna");
        actor = actors.get(0);
        List<Historical> refs = jdbc.query("""
                SELECT n.id,n.departamento_id,n.usuario_id FROM nombramientos_departamento n
                JOIN departamentos d ON d.id=n.departamento_id JOIN usuarios u ON u.id=n.usuario_id
                ORDER BY n.id LIMIT 1
                """, (r,i) -> new Historical(r.getLong(1),r.getLong(2),r.getLong(3)));
        assertFalse(refs.isEmpty(), "Falta referencia historica de nombramiento; no se inventara ni modificara");
        historical = refs.get(0); baseline = fingerprints();
    }
    @BeforeEach void ownFixture() throws Exception {
        SecurityContextHolder.clearContext();
        // Fallo de limpieza anterior: impedir nuevas escrituras y no perder los IDs registrados.
        assertNull(fixture,"Limpieza anterior incompleta o trabajadores activos: se bloquean los siguientes fixtures");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "No envolver la suite en @Transactional");
        reset(spyTarget()); fixture = new Fixture();
        fixture.date = LocalDate.now(clock).plusDays(10); fixture.folio = folio();
        inNew(() -> {
            fixture.space = insert("INSERT INTO espacios(nombre_espacio,ubicacion,capacidad,estado_actual,activo,minutos_limpieza) VALUES (?,?,20,'DISPONIBLE',1,30)", "IT-SUP-"+config.run+"-"+UUID.randomUUID(), "SOLO FIXTURE TEMPORAL");
            fixture.item = insert("INSERT INTO inventario_espacios(nombre_item,cantidad,estado_bien,espacio_id) VALUES ('IT SUPERVISOR',3,'BUENO',?)",fixture.space); return null;
        });
        fixture.oficio = createPdf(); fixture.requisicion = createPdf();
        inNew(() -> {
            fixture.id = addRequest(fixture.folio,fixture.date,LocalTime.of(10,0),LocalTime.of(12,0),"URGENTE_REQUISICION","PENDIENTE",fixture.oficio,fixture.requisicion);
            jdbc.update("INSERT INTO solicitud_recursos(solicitud_id,inventario_id,espacio_id,nombre_item,estado_bien,cantidad_solicitada,existencias_al_confirmar) VALUES (?,?,?,'IT SUPERVISOR','BUENO',1,3)",fixture.id,fixture.item,fixture.space); return null;
        });
    }
    @AfterEach void cleanOnlyOwnedFixtures() throws Exception {
        SecurityContextHolder.clearContext();
        if (fixture!=null && fixture.workersRunning) throw new IllegalStateException("Trabajadores potencialmente activos: NO limpiar ni reanudar Spring");
        reset(spyTarget()); if (fixture==null) return;
        try (Connection connection = dataSource.getConnection()) { SupervisorItSupport.verifyServer(connection); }
        inNew(() -> {
            for (var entry : fixture.requests.entrySet()) {
                List<Map<String,Object>> row = jdbc.queryForList("SELECT folio,espacio_id FROM solicitudes_espacio WHERE id=?",entry.getKey());
                if (!row.isEmpty()) {
                    assertEquals(entry.getValue(),row.get(0).get("folio"),"No limpiar solicitud cuya propiedad no coincide");
                    assertEquals(fixture.space,((Number)row.get(0).get("espacio_id")).longValue());
                    for (String table : List.of("revision_urgencias_eventos","solicitud_recursos","confirmaciones_reserva","consumo_cupos_reserva")) jdbc.update("DELETE FROM "+table+" WHERE solicitud_id=?",entry.getKey());
                    assertEquals(1,jdbc.update("DELETE FROM solicitudes_espacio WHERE id=? AND folio=?",entry.getKey(),entry.getValue()));
                }
            }
            if (fixture.item!=0) jdbc.update("DELETE FROM inventario_espacios WHERE id=? AND espacio_id=? AND nombre_item='IT SUPERVISOR'",fixture.item,fixture.space);
            if (fixture.space!=0) {
                List<String> names = jdbc.query("SELECT nombre_espacio FROM espacios WHERE id=?",(r,i) -> r.getString(1),fixture.space);
                if (!names.isEmpty()) { assertTrue(names.get(0).startsWith("IT-SUP-"+config.run+"-")); assertEquals(1,jdbc.update("DELETE FROM espacios WHERE id=? AND nombre_espacio=?",fixture.space,names.get(0))); }
            }
            return null;
        });
        for (Path file : fixture.files) { assertEquals(config.documents,file.getParent()); assertFalse(Files.isSymbolicLink(file)); Files.deleteIfExists(file); }
        fixture=null;
    }
    @AfterAll void baselineMustRemain() throws Exception {
        SecurityContextHolder.clearContext(); if (baseline==null) return;
        if (fixture!=null && fixture.workersRunning) throw new IllegalStateException("Trabajadores pendientes: no se afirma limpieza ni linea de base final");
        Map<String,byte[]> after = fingerprints();
        for (String table : baseline.keySet()) assertTrue(Arrays.equals(baseline.get(table),after.get(table)),"Cambio persistente fuera de fixtures en "+table+"; revisar localmente; no se imprimen filas");
        try (var files=Files.list(config.documents)) { assertEquals(List.of(SupervisorItSupport.MARKER),files.map(p -> p.getFileName().toString()).sorted().toList(),"Quedaron archivos IT; no hacer borrado recursivo"); }
    }

    @Test @Order(1) void falloEnSpyDespuesDeReintegroYAuditoriaRealRevierteTodoDesdeOtraTransaccion() {
        Snapshot before=snapshot(); var command=command("RECHAZAR"); AtomicBoolean reached=new AtomicBoolean(); AtomicLong failedConnection=new AtomicLong(-1);
        UrgenciaAuditWriter target=spyTarget();
        doAnswer(invocation -> {
            long request=invocation.getArgument(0); assertEquals(fixture.id,request); assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("RECHAZADA",em.createNativeQuery("SELECT estatus_solicitud FROM solicitudes_espacio WHERE id=:id").setParameter("id",request).getSingleResult());
            Object[] consumption=(Object[])em.createNativeQuery("SELECT fecha_reintegro,reintegrado_por_usuario_id,motivo_reintegro FROM consumo_cupos_reserva WHERE solicitud_id=:id").setParameter("id",request).getSingleResult();
            assertNotNull(consumption[0],"El spy se alcanzo antes del reintegro real"); assertEquals(actor.id,((Number)consumption[1]).longValue()); assertEquals(command.motivo(),consumption[2]);
            failedConnection.set(((Number)em.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue());
            // INSERT y flush productivos reales; despues se exige rollback de las TRES escrituras.
            invocation.callRealMethod();
            assertEquals(1L,((Number)em.createNativeQuery("SELECT COUNT(*) FROM revision_urgencias_eventos WHERE solicitud_id=:id AND clave_operacion=:key").setParameter("id",request).setParameter("key",command.clave()).getSingleResult()).longValue());
            reached.set(true); throw new InjectedAfterRefundAndAudit();
        }).when(target).append(eq(fixture.id),eq(actor.id),eq(command),anyString(),anyLong(),anyString(),anyString(),any(UrgenciaPolicy.Transition.class));
        try {
            assertThrows(InjectedAfterRefundAndAudit.class,() -> as(actor,() -> service.apply(fixture.id,command)),"Debe ser exactamente el fallo inyectado, no uno anterior"); assertTrue(reached.get(),"No se alcanzo el punto critico confirmado");
            verify(target,times(1)).append(eq(fixture.id),eq(actor.id),eq(command),anyString(),anyLong(),anyString(),anyString(),any(UrgenciaPolicy.Transition.class));
        } finally { reset(target); }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); AtomicLong verifierConnection=new AtomicLong();
        Snapshot after=inNew(() -> { verifierConnection.set(number("SELECT CONNECTION_ID()")); return snapshotInside(); });
        assertTrue(failedConnection.get()>0 && verifierConnection.get()!=failedConnection.get(),"Verificar desde otra transaccion y conexion"); assertEquals(before,after,"Solicitud, consumo, confirmacion, detalles y auditoria deben conservarse");
    }
    @Test @Order(2) void rechazoAtomicoYReplayExactoNoDuplican() {
        var c=command("RECHAZAR"); long counter=number("SELECT ultimo_consecutivo FROM contador_folios_reserva WHERE id=1"); int quota=jdbc.queryForObject("SELECT cupo_mensual FROM departamentos WHERE id=?",Integer.class,historical.department);
        var result=as(actor,() -> service.apply(fixture.id,c)); assertEquals("RECHAZADA",result.estado()); assertEquals("NO_APLICA",result.documentos()); assertFalse(result.repetida());
        Snapshot applied=snapshot(); assertNotNull(applied.consumption.get("fecha_reintegro")); assertEquals(actor.id,((Number)applied.consumption.get("reintegrado_por_usuario_id")).longValue());
        assertEquals(1,applied.audit.size()); assertEquals(c.clave(),applied.audit.get(0).get("clave_operacion")); assertEquals(1L,((Number)applied.audit.get(0).get("revision_despues")).longValue()); assertTrue(as(actor,() -> service.history(fixture.id)).get(0).reintegrado());
        var repeated=as(actor,() -> service.apply(fixture.id,c)); assertTrue(repeated.repetida()); assertEquals(result.revision(),repeated.revision()); assertEquals(applied,snapshot());
        assertEquals(counter,number("SELECT ultimo_consecutivo FROM contador_folios_reserva WHERE id=1")); assertEquals(quota,jdbc.queryForObject("SELECT cupo_mensual FROM departamentos WHERE id=?",Integer.class,historical.department));
    }
    @Test @Order(3) void mismoUuidConPayloadDistintoSeRechazaSinCambios() {
        var c=command("RECHAZAR"); as(actor,() -> service.apply(fixture.id,c)); Snapshot before=snapshot();
        var changed=new UrgenciaPolicy.Command(c.clave(),c.versionEsperada(),c.accion(),c.motivo()+" cambiado",true); conflict(() -> as(actor,() -> service.apply(fixture.id,changed))); assertEquals(before,snapshot());
    }
    @Test @Order(4) void mismoUuidPorOtroActorAutorizadoSeRechazaSinCambios() {
        assumeTrue(actors.size()>=2,"PENDIENTE: no existen dos cuentas autorizadas activas; no se crearan cuentas"); var c=command("RECHAZAR"); as(actor,() -> service.apply(fixture.id,c)); Snapshot before=snapshot(); conflict(() -> as(actors.get(1),() -> service.apply(fixture.id,c))); assertEquals(before,snapshot());
    }
    @Test @Order(5) void versionObsoletaFallaAntesDeAuditoria() {
        var old=command("RECHAZAR"); inNew(() -> { jdbc.update("UPDATE solicitudes_espacio SET motivo_evento='FIXTURE MODIFICADO' WHERE id=? AND folio=?",fixture.id,fixture.folio); return null; }); Snapshot before=snapshot();
        var error=conflict(() -> as(actor,() -> service.apply(fixture.id,old))); assertEquals("La reserva cambió; vuelve a consultar antes de decidir",error.getReason()); verifyNoInteractions(spyTarget()); assertEquals(before,snapshot());
    }
    @Test @Order(6) void decisionesConcurrentesSoloConfirmanUnaRevisionYUnReintegro() throws Exception {
        var first=command("RECHAZAR"); var second=new UrgenciaPolicy.Command(UUID.randomUUID().toString(),first.versionEsperada(),"RECHAZAR","SEGUNDA DECISION CONCURRENTE",true);
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1); fixture.workersRunning=true;
        try {
            List<Future<Integer>> futures=new ArrayList<>();
            for (var c:List.of(first,second)) futures.add(pool.submit(() -> { ready.countDown(); if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("No se sincronizaron trabajadores"); try { as(actor,() -> service.apply(fixture.id,c)); return 200; } catch (ResponseStatusException ex) { return ex.getStatusCode().value(); } }));
            assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown(); List<Integer> codes=new ArrayList<>(); for (Future<Integer> future:futures) codes.add(future.get(90,TimeUnit.SECONDS)); Collections.sort(codes); assertEquals(List.of(200,409),codes);
        } finally { start.countDown(); pool.shutdownNow(); boolean stopped=pool.awaitTermination(40,TimeUnit.SECONDS); fixture.workersRunning=!stopped; assertTrue(stopped,"Trabajadores activos: NO limpiar fixtures ni reanudar la aplicacion"); }
        var state=snapshot(); assertEquals("RECHAZADA",state.request.get("estatus_solicitud")); assertEquals(1,state.audit.size()); assertNotNull(state.consumption.get("fecha_reintegro"));
    }
    @Test @Order(7) void aprobarLeePdfRealesYNoConsumeOtroCupo() {
        var before=snapshot(); List<Map<String,Object>> stock=jdbc.queryForList("SELECT * FROM inventario_espacios WHERE id=?",fixture.item);
        var result=as(actor,() -> service.apply(fixture.id,command("APROBAR"))); assertEquals("APROBADA",result.estado()); assertEquals("VALIDADA",result.documentos()); var after=snapshot();
        assertEquals(before.consumption,after.consumption); assertEquals(before.confirmation,after.confirmation); assertEquals(before.resources,after.resources); assertEquals(stock,jdbc.queryForList("SELECT * FROM inventario_espacios WHERE id=?",fixture.item));
        var events=as(actor,() -> service.history(fixture.id)); assertEquals(1,events.size()); assertFalse(events.get(0).reintegrado());
    }
    @Test @Order(8) void observarUrgenteInventarioAprobadaNoCancelaNiReintegra() {
        inNew(() -> { jdbc.update("UPDATE confirmaciones_reserva SET modalidad='URGENTE_INVENTARIO' WHERE solicitud_id=?",fixture.id); jdbc.update("UPDATE solicitudes_espacio SET estatus_solicitud='APROBADA',ruta_requisicion=NULL WHERE id=?",fixture.id); return null; });
        var before=snapshot(); var initial=command("OBSERVAR_DOCUMENTOS"); var c=new UrgenciaPolicy.Command(initial.clave(),initial.versionEsperada(),initial.accion(),"Oficio incorrecto: observacion de fixture",false);
        var result=as(actor,() -> service.apply(fixture.id,c)); assertEquals("APROBADA",result.estado()); assertEquals("OBSERVADA",result.documentos()); assertEquals(before.consumption,snapshot().consumption); assertFalse(as(actor,() -> service.history(fixture.id)).get(0).reintegrado());
        // NO afirma notificacion, sustitucion de PDF ni aprobacion automatica de la captura original.
    }
    @Test @Order(9) void normalNoSeListaNiPermiteHistorialDocumentoODecision() {
        var c=command("RECHAZAR"); inNew(() -> { jdbc.update("UPDATE confirmaciones_reserva SET modalidad='NORMAL',motivo_urgencia=NULL WHERE solicitud_id=?",fixture.id); return null; }); var before=snapshot();
        assertTrue(as(actor,() -> service.list(YearMonth.from(fixture.date).toString())).solicitudes().stream().noneMatch(r -> r.id()==fixture.id));
        for (Runnable operation:List.<Runnable>of(() -> as(actor,() -> service.history(fixture.id)),() -> as(actor,() -> service.document(fixture.id,"oficio")),() -> as(actor,() -> service.apply(fixture.id,c)))) assertEquals(404,assertThrows(ResponseStatusException.class,operation::run).getStatusCode().value());
        assertEquals(before,snapshot());
    }
    @Test @Order(10) void pdfFaltanteOInvalidoImpideAprobacionSinEscrituras() throws Exception {
        Path oficio=config.documents.resolve(fixture.oficio); Files.delete(oficio); var before=snapshot(); conflict(() -> as(actor,() -> service.apply(fixture.id,command("APROBAR")))); assertEquals(before,snapshot());
        Files.writeString(oficio,"%PDF-no es un PDF valido",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW); conflict(() -> as(actor,() -> service.apply(fixture.id,command("APROBAR")))); assertEquals(before,snapshot());
    }
    @Test @Order(11) void inventarioMaloOInsuficienteYEspacioNoDisponibleImpidenAprobar() {
        for (String sql:List.of("UPDATE inventario_espacios SET estado_bien='MALO' WHERE id=?","UPDATE inventario_espacios SET estado_bien='BUENO',cantidad=0 WHERE id=?")) { inNew(() -> { jdbc.update(sql,fixture.item); return null; }); var before=snapshot(); conflict(() -> as(actor,() -> service.apply(fixture.id,command("APROBAR")))); assertEquals(before,snapshot()); }
        inNew(() -> { jdbc.update("UPDATE inventario_espacios SET cantidad=3,estado_bien='BUENO' WHERE id=?",fixture.item); return null; });
        for (String sql:List.of("UPDATE espacios SET activo=0 WHERE id=?","UPDATE espacios SET activo=1,estado_actual='MANTENIMIENTO' WHERE id=?")) { inNew(() -> { jdbc.update(sql,fixture.space); return null; }); var before=snapshot(); conflict(() -> as(actor,() -> service.apply(fixture.id,command("APROBAR")))); assertEquals(before,snapshot()); }
    }
    @Test @Order(12) void limpiezaCruzaAmbosLadosIncluyendoVecinoNormal() {
        AtomicLong neighbour=new AtomicLong(); inNew(() -> { neighbour.set(addRequest(folio(),fixture.date,LocalTime.of(8,0),LocalTime.of(9,45),"NORMAL","APROBADA",null,null)); return null; });
        var before=snapshot(); conflict(() -> as(actor,() -> service.apply(fixture.id,command("APROBAR")))); assertEquals(before,snapshot());
        inNew(() -> { jdbc.update("UPDATE solicitudes_espacio SET hora_inicio='12:15:00',hora_fin='14:00:00' WHERE id=?",neighbour.get()); return null; }); before=snapshot(); conflict(() -> as(actor,() -> service.apply(fixture.id,command("APROBAR")))); assertEquals(before,snapshot());
        inNew(() -> { jdbc.update("UPDATE solicitudes_espacio SET hora_inicio='12:30:00',hora_fin='14:00:00' WHERE id=?",neighbour.get()); return null; }); assertEquals("APROBADA",as(actor,() -> service.apply(fixture.id,command("APROBAR"))).estado(),"Tocar el borde despues de 30 minutos debe permitirse");
    }
    @Test @Order(13) void restriccionesRealesImpidenLimpiezaNulaCeroNegativaSinRelajarEsquema() {
        for (Object invalid:new Object[]{null,0,-1}) { assertThrows(DataIntegrityViolationException.class,() -> inNew(() -> { jdbc.update("UPDATE espacios SET minutos_limpieza=? WHERE id=?",invalid,fixture.space); return null; })); assertEquals(30,jdbc.queryForObject("SELECT minutos_limpieza FROM espacios WHERE id=?",Integer.class,fixture.space)); }
        // Restricciones DB, NO ramas defensivas del servicio sobre un esquema corrupto.
    }
    @Test @Order(14) void autoridadesDeSesionNoAutorizanCuentaSolicitanteEnServicio() {
        List<Actor> requesters=loadOnlyRequesters(); assumeTrue(!requesters.isEmpty(),"PENDIENTE: no existe cuenta activa solo SOLICITANTE"); Actor requester=requesters.get(0); var c=command("RECHAZAR"); var before=snapshot();
        var error=assertThrows(ResponseStatusException.class,() -> as(new Actor(requester.id,requester.email,List.of("SUPERVISOR")),() -> service.apply(fixture.id,c))); assertEquals(HttpStatus.FORBIDDEN.value(),error.getStatusCode().value()); assertEquals(before,snapshot());
        // Contexto construido de servicio: NO se presenta como login HTTP.
    }

    @Test @Order(20) @EnabledIfEnvironmentVariable(named="BITACORA_SUPERVISOR_HTTP", matches="SI")
    void httpRealLoginCsrfSesionNuevaYReplayMismoPayload() throws Exception {
        String email=SupervisorItSupport.required("BITACORA_SUPERVISOR_HTTP_CORREO"); assertTrue(actors.stream().anyMatch(a -> a.email.equals(email)),"Login HTTP requiere cuenta existente autorizada activa"); String password=SupervisorItSupport.required("BITACORA_SUPERVISOR_HTTP_PASSWORD");
        assertEquals(302,get(client(),"/supervisor/api/urgencias/csrf").statusCode()); HttpClient http=client(); login(http,email,password); Token token=fresh(http);
        var c=command("RECHAZAR"); String body=json.writeValueAsString(c); var before=snapshot();
        assertEquals(403,post(http,"/supervisor/api/urgencias/"+fixture.id+"/revision",body,null).statusCode()); assertEquals(403,post(http,"/supervisor/api/urgencias/"+fixture.id+"/revision",body,new Token(token.header,"invalido")).statusCode()); assertEquals(before,snapshot());
        var pdf=get(http,"/supervisor/api/urgencias/"+fixture.id+"/documentos/oficio"); assertEquals(200,pdf.statusCode()); assertTrue(pdf.headers().firstValue("Content-Type").orElse("").startsWith("application/pdf")); assertEquals("nosniff",pdf.headers().firstValue("X-Content-Type-Options").orElse("")); assertTrue(pdf.headers().firstValue("Content-Disposition").orElse("").startsWith("attachment;"));
        var response=post(http,"/supervisor/api/urgencias/"+fixture.id+"/revision",body,token); assertEquals(200,response.statusCode()); assertEquals(Boolean.FALSE,object(response.body()).get("repetida")); Snapshot applied=snapshot();
        var history=get(http,"/supervisor/api/urgencias/"+fixture.id+"/historial"); assertEquals(200,history.statusCode()); List<?> events=json.readValue(history.body(),List.class); assertEquals(c.clave(),((Map<?,?>)events.get(0)).get("clave")); assertFalse(history.body().contains("firma_payload")); assertFalse(history.body().contains(fixture.oficio));
        Token logoutToken=fresh(http); var logout=http.send(HttpRequest.newBuilder(uri("/logout")).timeout(Duration.ofSeconds(40)).header(logoutToken.header,logoutToken.value).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()); assertEquals(302,logout.statusCode()); assertEquals(302,get(http,"/supervisor/api/urgencias/csrf").statusCode());
        // Login real nuevo; no cambiar UUID ni un byte del JSON.
        login(http,email,password); assertEquals(403,post(http,"/supervisor/api/urgencias/"+fixture.id+"/revision",body,token).statusCode()); var replay=post(http,"/supervisor/api/urgencias/"+fixture.id+"/revision",body,fresh(http)); assertEquals(200,replay.statusCode()); assertEquals(Boolean.TRUE,object(replay.body()).get("repetida")); assertEquals(applied,snapshot());
    }
    @Test @Order(21) @EnabledIfEnvironmentVariable(named="BITACORA_SUPERVISOR_HTTP_SOLICITANTE", matches="SI")
    void solicitanteConLoginRealNoAccedeADocumentosNiDecisiones() throws Exception {
        String email=SupervisorItSupport.required("BITACORA_SUPERVISOR_SOLICITANTE_CORREO"); assertTrue(loadOnlyRequesters().stream().anyMatch(a -> a.email.equals(email)),"Requiere cuenta existente activa SOLO SOLICITANTE"); HttpClient http=client(); login(http,email,SupervisorItSupport.required("BITACORA_SUPERVISOR_SOLICITANTE_PASSWORD"));
        Token token=token(get(http,"/login")); var before=snapshot(); assertEquals(403,get(http,"/supervisor/api/urgencias/csrf").statusCode()); assertEquals(403,get(http,"/supervisor/api/urgencias/"+fixture.id+"/documentos/oficio").statusCode()); assertEquals(403,post(http,"/supervisor/api/urgencias/"+fixture.id+"/revision",json.writeValueAsString(command("RECHAZAR")),token).statusCode()); assertEquals(before,snapshot());
    }

    private record Actor(long id,String email,List<String> roles) { }
    private record Historical(long appointment,long department,long user) { }
    private record Snapshot(Map<String,Object> request,Map<String,Object> consumption,List<Map<String,Object>> confirmation,List<Map<String,Object>> resources,List<Map<String,Object>> audit) { }
    private static final class Fixture { long id,space,item; LocalDate date; String folio,oficio,requisicion; volatile boolean workersRunning; final LinkedHashMap<Long,String> requests=new LinkedHashMap<>(); final LinkedHashSet<Path> files=new LinkedHashSet<>(); }
    private static final class InjectedAfterRefundAndAudit extends RuntimeException { }
    private record Token(String header,String value) { @Override public String toString() { return "Token CSRF [oculto]"; } }
    private String folio() { return "ITSUP-"+UUID.randomUUID().toString().replace("-","").substring(0,24); }
    private long insert(String sql,Object... args) {
        GeneratedKeyHolder keys=new GeneratedKeyHolder(); assertEquals(1,jdbc.update(connection -> { PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS); for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]); return statement; },keys)); Number key=keys.getKey(); assertNotNull(key); return key.longValue();
    }
    private long addRequest(String folio,LocalDate day,LocalTime start,LocalTime end,String mode,String state,String oficio,String requisicion) {
        long id=insert("""
                INSERT INTO solicitudes_espacio(folio,usuario_id,espacio_id,fecha_evento,hora_inicio,hora_fin,motivo_evento,
                    es_urgente_sin_oficio,estatus_solicitud,tipo_origen,departamento_solicitante_id,nombramiento_id,
                    registrado_por_usuario_id,ruta_oficio_reservacion,ruta_requisicion)
                VALUES (?,?,?,?,?,?,'FIXTURE SUPERVISOR - NO ES EVENTO REAL',1,?,'SOLICITUD_DOCENTE',?,?,?,?,?)
                """,folio,historical.user,fixture.space,day,start,end,state,historical.department,historical.appointment,actor.id,oficio,requisicion);
        fixture.requests.put(id,folio); // Registrar antes de posteriores escrituras, incluso ante rollback.
        jdbc.update("INSERT INTO consumo_cupos_reserva(solicitud_id) VALUES (?)",id);
        jdbc.update("INSERT INTO confirmaciones_reserva(clave_confirmacion,solicitud_id,registrado_por_usuario_id,firma_solicitud,modalidad,motivo_urgencia) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),id,actor.id,"0".repeat(64),mode,"NORMAL".equals(mode)?null:"FIXTURE URGENTE"); return id;
    }
    private String createPdf() throws Exception {
        String name=UUID.randomUUID()+".pdf"; Path file=config.documents.resolve(name);
        try (OutputStream output=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) { fixture.files.add(file); try (PDDocument pdf=new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(output); } } return name;
    }
    private UrgenciaPolicy.Command command(String action) { var row=as(actor,() -> service.list(YearMonth.from(fixture.date).toString())).solicitudes().stream().filter(r -> r.id()==fixture.id).findFirst().orElseThrow(); return new UrgenciaPolicy.Command(UUID.randomUUID().toString(),row.version(),action,"REVISION FIXTURE SUPERVISOR",true); }
    private Snapshot snapshot() { return inNew(this::snapshotInside); }
    private Snapshot snapshotInside() { return new Snapshot(jdbc.queryForMap("SELECT * FROM solicitudes_espacio WHERE id=?",fixture.id),jdbc.queryForMap("SELECT * FROM consumo_cupos_reserva WHERE solicitud_id=?",fixture.id),jdbc.queryForList("SELECT * FROM confirmaciones_reserva WHERE solicitud_id=?",fixture.id),jdbc.queryForList("SELECT * FROM solicitud_recursos WHERE solicitud_id=? ORDER BY id",fixture.id),jdbc.queryForList("SELECT * FROM revision_urgencias_eventos WHERE solicitud_id=? ORDER BY revision_despues",fixture.id)); }
    private <T> T inNew(Supplier<T> work) { TransactionTemplate tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(30); return tx.execute(status -> work.get()); }
    private <T> T as(Actor who,Supplier<T> work) { var previous=SecurityContextHolder.getContext(); var context=SecurityContextHolder.createEmptyContext(); context.setAuthentication(new UsernamePasswordAuthenticationToken(who.email,null,who.roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_"+r)).toList())); SecurityContextHolder.setContext(context); try { return work.get(); } finally { SecurityContextHolder.setContext(previous); } }
    private ResponseStatusException conflict(Runnable work) { var error=assertThrows(ResponseStatusException.class,work::run); assertEquals(409,error.getStatusCode().value()); return error; }
    private UrgenciaAuditWriter spyTarget() { UrgenciaAuditWriter target=AopTestUtils.getUltimateTargetObject(audit); if (Mockito.mockingDetails(target).isSpy()) return target; assertTrue(Mockito.mockingDetails(audit).isSpy(),"No se encontro spy real; no sustituir servicio por mock"); return audit; }
    private long number(String sql,Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args)); }
    private List<Actor> loadActors() { return jdbc.query("""
                SELECT DISTINCT u.id,u.correo_institucional FROM usuarios u JOIN usuario_roles ur ON ur.usuario_id=u.id JOIN roles r ON r.id=ur.rol_id
                WHERE u.activo=1 AND r.nombre_rol='SUPERVISOR' ORDER BY u.id
                """,(r,i) -> new Actor(r.getLong(1),r.getString(2),roles(r.getLong(1)))); }
    private List<String> roles(long id) { return jdbc.query("SELECT r.nombre_rol FROM roles r JOIN usuario_roles ur ON ur.rol_id=r.id WHERE ur.usuario_id=? ORDER BY r.id",(r,i) -> r.getString(1),id); }
    private List<Actor> loadOnlyRequesters() { return jdbc.query("""
                SELECT u.id,u.correo_institucional FROM usuarios u WHERE u.activo=1
                AND EXISTS (SELECT 1 FROM usuario_roles ur JOIN roles r ON r.id=ur.rol_id WHERE ur.usuario_id=u.id AND r.nombre_rol='SOLICITANTE')
                AND NOT EXISTS (SELECT 1 FROM usuario_roles ur JOIN roles r ON r.id=ur.rol_id WHERE ur.usuario_id=u.id AND r.nombre_rol IN ('ADMINISTRADOR','SUPERVISOR')) ORDER BY u.id
                """,(r,i) -> new Actor(r.getLong(1),r.getString(2),roles(r.getLong(1)))); }
    private Map<String,byte[]> fingerprints() throws Exception {
        Map<String,byte[]> result=new LinkedHashMap<>();
        try (Connection connection=dataSource.getConnection()) {
            SupervisorItSupport.verifyServer(connection);
            for (String table:SupervisorItSupport.TABLES) {
                MessageDigest digest=MessageDigest.getInstance("SHA-256"); String order=table.equals("usuario_roles")?"usuario_id,rol_id":table.equals("consumo_cupos_reserva")?"solicitud_id":table.equals("confirmaciones_reserva")?"clave_confirmacion":table.equals("flyway_schema_history")?"installed_rank":"id";
                try (Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery("SELECT * FROM `"+table+"` ORDER BY "+order)) {
                    int columns=rows.getMetaData().getColumnCount(),count=0;
                    while (rows.next()) { assertTrue(++count<=100000,"Linea de base demasiado grande: requiere revision local"); digest.update((byte)0x7e);
                        for (int i=1;i<=columns;i++) { String value=rows.getString(i); if (value==null) { digest.update((byte)0); continue; } digest.update((byte)1); byte[] bytes=value.getBytes(StandardCharsets.UTF_8); digest.update(new byte[]{(byte)(bytes.length>>>24),(byte)(bytes.length>>>16),(byte)(bytes.length>>>8),(byte)bytes.length}); digest.update(bytes); }
                    }
                }
                result.put(table,digest.digest());
            }
        }
        return result;
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:"+port+path); }
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER)).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build(); }
    private HttpResponse<String> get(HttpClient http,String path) throws Exception { return http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(40)).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    private HttpResponse<String> post(HttpClient http,String path,String body,Token token) throws Exception { var request=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(40)).header("Content-Type","application/json"); if (token!=null) request.header(token.header,token.value); return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    @SuppressWarnings("unchecked") private Map<String,Object> object(String text) throws Exception { return json.readValue(text,Map.class); }
    private Token token(HttpResponse<String> response) throws Exception { assertEquals(200,response.statusCode()); var data=object(response.body()); String header=Objects.toString(data.get("headerName"),""),value=Objects.toString(data.get("token"),""); assertTrue(header.matches("[A-Za-z0-9-]+") && !value.isBlank(),"No se obtuvo CSRF real; valores ocultos"); return new Token(header,value); }
    private Token fresh(HttpClient http) throws Exception { var response=get(http,"/supervisor/api/urgencias/csrf"); assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store")); return token(response); }
    private void login(HttpClient http,String email,String password) throws Exception {
        Token token=token(get(http,"/login")); String body="username="+URLEncoder.encode(email,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8);
        var response=http.send(HttpRequest.newBuilder(uri("/web/login")).timeout(Duration.ofSeconds(40)).header("Content-Type","application/x-www-form-urlencoded").header(token.header,token.value).POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(302,response.statusCode(),"El filtro real de login debe redirigir"); assertTrue(response.headers().firstValue("Location").orElse("").endsWith("/bitacoras"),"Fallo de autenticacion real; no se imprimen credenciales ni tokens");
    }
}
