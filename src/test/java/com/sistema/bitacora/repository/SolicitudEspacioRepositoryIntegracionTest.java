package com.sistema.bitacora.repository;

import com.sistema.bitacora.entity.Departamento;
import com.sistema.bitacora.entity.Espacio;
import com.sistema.bitacora.entity.SolicitudEspacio;
import com.sistema.bitacora.entity.Usuario;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integracion del repositorio con MariaDB real, SOLO en la base de pruebas.
 * Flyway crea sus tablas una vez. Las inserciones de cada prueba se revierten.
 * No prueba el guardado final, folios oficiales, seguridad web ni concurrencia.
 */
@org.junit.jupiter.api.Disabled("Pendiente configurar una instancia independiente de pruebas")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Rollback
class SolicitudEspacioRepositoryIntegracionTest {

    private static final String BASE = "bitacora_espacios_pruebas";
    private static final String URL =
            "jdbc:mariadb://127.0.0.1:3306/" + BASE + "?connectionTimeZone=UTC";
    private static final LocalDate FECHA = LocalDate.of(2026, 11, 10);

    @DynamicPropertySource
    static void configurarBaseAislada(DynamicPropertyRegistry propiedades) {
        String usuario = System.getenv("BITACORA_TEST_DB_USER");
        String clave = System.getenv("BITACORA_TEST_DB_PASSWORD");
        if (usuario == null || !usuario.matches("bt_[a-f0-9]{8}")
                || clave == null || clave.isBlank()) {
            throw new IllegalStateException(
                    "Faltan las credenciales restringidas de prueba en esta ventana. "
                            + "No se permite usar root ni la conexion normal de la aplicacion.");
        }

        // Sin valores alternativos: nunca utiliza la conexion normal por omision.
        propiedades.add("spring.datasource.url", () -> URL);
        propiedades.add("spring.datasource.username", () -> usuario);
        propiedades.add("spring.datasource.password", () -> clave);
        propiedades.add("spring.datasource.driver-class-name",
                () -> "org.mariadb.jdbc.Driver");
        propiedades.add("spring.datasource.hikari.maximum-pool-size", () -> "2");

        propiedades.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        propiedades.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.community.dialect.MariaDBLegacyDialect");
        propiedades.add("spring.sql.init.mode", () -> "never");

        // Las migraciones se ejecutan exclusivamente con la cuenta restringida.
        propiedades.add("spring.flyway.enabled", () -> "true");
        propiedades.add("spring.flyway.url", () -> URL);
        propiedades.add("spring.flyway.user", () -> usuario);
        propiedades.add("spring.flyway.password", () -> clave);
        propiedades.add("spring.flyway.default-schema", () -> BASE);
        propiedades.add("spring.flyway.schemas", () -> BASE);
        propiedades.add("spring.flyway.locations", () -> "classpath:db/migration");
        propiedades.add("spring.flyway.create-schemas", () -> "false");
        propiedades.add("spring.flyway.clean-disabled", () -> "true");
        propiedades.add("spring.flyway.baseline-on-migrate", () -> "false");
    }

    @Autowired
    private EntityManager entidad;

    @Autowired
    private SolicitudEspacioRepository repositorio;

    private Usuario solicitante;
    private Espacio espacio;
    private Espacio otroEspacio;

    @BeforeEach
    void prepararDatosDePrueba() {
        // Comprobacion antes de insertar los datos de cada prueba.
        assertEquals(BASE, entidad.createNativeQuery("SELECT DATABASE()")
                .getSingleResult().toString());

        String sufijo = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Departamento departamento = new Departamento();
        departamento.setNombreDepartamento("Prueba " + sufijo);
        departamento.setClaveDepartamento("T" + sufijo);
        departamento.setActivo(true);
        entidad.persist(departamento);

        solicitante = new Usuario();
        solicitante.setNombreCompleto("Usuario ficticio de integracion");
        solicitante.setCorreoInstitucional("test-" + sufijo + "@ejemplo.test");
        solicitante.setPasswordHash("NO_ES_UNA_CREDENCIAL_UTILIZABLE");
        solicitante.setDepartamento(departamento);
        solicitante.setActivo(true);
        entidad.persist(solicitante);

        espacio = crearEspacio("Sala prueba " + sufijo);
        otroEspacio = crearEspacio("Otra sala prueba " + sufijo);
        entidad.flush();
    }

    @Test
    void conexionUsaExclusivamenteLaBaseYCuentaDePruebas() {
        assertEquals(BASE, entidad.createNativeQuery("SELECT DATABASE()")
                .getSingleResult().toString());
        String cuenta = entidad.createNativeQuery("SELECT CURRENT_USER()")
                .getSingleResult().toString();
        assertTrue(cuenta.startsWith(System.getenv("BITACORA_TEST_DB_USER") + "@"));
    }

    @Test
    void consultaIncluyeFechasLimiteYExcluyeOtroEspacioYFechasFuera() {
        LocalDate anterior = FECHA.minusDays(1);
        LocalDate siguiente = FECHA.plusDays(1);

        SolicitudEspacio a = crearSolicitud(espacio, anterior, "23:00", "23:50", "APROBADA");
        SolicitudEspacio b = crearSolicitud(espacio, FECHA, "10:00", "12:00", "PENDIENTE");
        SolicitudEspacio c = crearSolicitud(espacio, siguiente, "00:10", "01:00", "PENDIENTE");
        crearSolicitud(espacio, anterior.minusDays(1), "10:00", "11:00", "APROBADA");
        crearSolicitud(espacio, siguiente.plusDays(1), "10:00", "11:00", "APROBADA");
        crearSolicitud(otroEspacio, FECHA, "10:00", "12:00", "APROBADA");
        Long espacioId = espacio.getId();
        List<Long> esperados = List.of(a.getId(), b.getId(), c.getId());
        entidad.flush();
        entidad.clear();

        var encontrados = repositorio.buscarParaComprobarDisponibilidad(
                espacioId, anterior, siguiente);
        assertEquals(esperados, encontrados.stream().map(SolicitudEspacio::getId).toList());
    }

    @Test
    void consultaNuevaOrdenaPorHoraYNoOcultaEstadosAlServicio() {
        SolicitudEspacio tarde = crearSolicitud(espacio, FECHA, "15:00", "16:00", "APROBADA");
        SolicitudEspacio cancelada = crearSolicitud(espacio, FECHA, "09:00", "10:00", "CANCELADA");
        SolicitudEspacio rechazada = crearSolicitud(espacio, FECHA, "11:00", "12:00", "RECHAZADA");
        Long espacioId = espacio.getId();
        List<Long> esperados = List.of(cancelada.getId(), rechazada.getId(), tarde.getId());
        entidad.flush();
        entidad.clear();

        var encontrados = repositorio.buscarParaComprobarDisponibilidad(
                espacioId, FECHA, FECHA);
        assertEquals(esperados, encontrados.stream().map(SolicitudEspacio::getId).toList());
        // Es el servicio, no esta consulta, quien decide si un estado bloquea.
    }

    @Test
    void consultaAnteriorConservadaDetectaCrucesDePendientesYAprobadas() {
        SolicitudEspacio aprobada = crearSolicitud(espacio, FECHA, "10:00", "12:00", "APROBADA");
        SolicitudEspacio pendiente = crearSolicitud(espacio, FECHA, "10:00", "12:00", "PENDIENTE");
        crearSolicitud(espacio, FECHA, "10:00", "12:00", "CANCELADA");
        crearSolicitud(espacio, FECHA, "10:00", "12:00", "RECHAZADA");
        crearSolicitud(otroEspacio, FECHA, "10:00", "12:00", "APROBADA");
        Long espacioId = espacio.getId();
        List<Long> esperados = List.of(aprobada.getId(), pendiente.getId()).stream().sorted().toList();
        entidad.flush();
        entidad.clear();

        var encontrados = repositorio.buscarConflictosDeHorario(
                espacioId, FECHA, LocalTime.of(11, 0), LocalTime.of(13, 0));
        assertEquals(esperados,
                encontrados.stream().map(SolicitudEspacio::getId).sorted().toList());
        assertTrue(repositorio.buscarConflictosDeHorario(
                espacioId, FECHA, LocalTime.of(12, 0), LocalTime.of(13, 0)).isEmpty());
        // Esta consulta antigua solo comprueba eventos, no sus margenes de limpieza.
    }

    private Espacio crearEspacio(String nombre) {
        Espacio nuevo = new Espacio();
        nuevo.setNombreEspacio(nombre);
        nuevo.setUbicacion("Ubicacion ficticia de pruebas");
        nuevo.setCapacidad(40);
        nuevo.setActivo(true);
        nuevo.setEstadoActual("DISPONIBLE");
        nuevo.setMinutosLimpieza(30);
        entidad.persist(nuevo);
        return nuevo;
    }

    private SolicitudEspacio crearSolicitud(
            Espacio sala, LocalDate fecha, String inicio, String fin, String estado) {
        SolicitudEspacio nueva = new SolicitudEspacio();
        // Identificador ficticio de fixture: NO usa ni prueba el generador de folios.
        nueva.setFolio("TEST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        nueva.setUsuario(solicitante);
        nueva.setEspacio(sala);
        nueva.setFechaEvento(fecha);
        nueva.setHoraInicio(LocalTime.parse(inicio));
        nueva.setHoraFin(LocalTime.parse(fin));
        nueva.setMotivoEvento("Evento ficticio de prueba de repositorio");
        nueva.setEstatusSolicitud(estado);
        entidad.persist(nueva);
        return nueva;
    }
}
