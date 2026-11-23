package com.sistema.bitacora.supervisorrevision;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Solo src/test/java. No usar desde codigo productivo. */
final class SupervisorItSupport {
    static final String URL = "jdbc:mariadb://127.0.0.1:3308/bitacora_pruebas_confirmacion?connectionTimeZone=UTC";
    static final String SCHEMA = "bitacora_pruebas_confirmacion";
    static final String DATADIR = "C:/Users/warri/rescate-bitacora/restauracion_limpia_20261103_130538_668/datos";
    static final String MARKER = ".supervisor-it-propietario";
    static final List<String> TABLES = List.of("bitacora_espacios", "departamentos", "dictamenes", "espacios",
            "historial_usuarios", "roles", "solicitudes_espacio", "usuarios", "usuario_roles",
            "inventario_espacios", "nombramientos_departamento", "contador_folios_reserva",
            "consumo_cupos_reserva", "solicitud_recursos", "confirmaciones_reserva",
            "revision_urgencias_eventos", "flyway_schema_history");
    static final List<String> WRITTEN = List.of("espacios", "inventario_espacios", "solicitudes_espacio",
            "solicitud_recursos", "consumo_cupos_reserva", "confirmaciones_reserva", "revision_urgencias_eventos");

    private SupervisorItSupport() { }

    static final class Config {
        final String user;
        final String password;
        final String run;
        final Path documents;
        Config(String user, String password, String run, Path documents) {
            this.user = user; this.password = password; this.run = run; this.documents = documents;
        }
        @Override public String toString() { return "Supervisor IT [credenciales ocultas]"; }
    }

    static String required(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank(), "Falta una variable requerida: " + name);
        return value;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static void exactUrl(String source, String value) {
        // Igualdad completa: no hosts alternos, esquemas escapados, multi-host ni parametros adicionales.
        require(URL.equals(value), "URL no permitida en " + source + "; no se creara el DataSource");
    }

    static Config config(boolean withDocuments) {
        exactUrl("SPRING_DATASOURCE_URL", required("SPRING_DATASOURCE_URL"));
        exactUrl("SPRING_FLYWAY_URL", required("SPRING_FLYWAY_URL"));
        for (String key : List.of("spring.datasource.url", "spring.flyway.url")) {
            String value = System.getProperty(key);
            if (value != null) exactUrl("propiedad JVM " + key, value);
        }
        String user = required("BITACORA_SUPERVISOR_DB_USER");
        String password = required("BITACORA_SUPERVISOR_DB_PASSWORD");
        String run = required("BITACORA_SUPERVISOR_RUN_ID");
        require(UUID.fromString(run).toString().equals(run), "Identificador de ejecucion no valido");
        Path documents = withDocuments ? documents(run) : null;
        return new Config(user, password, run, documents);
    }

    static Path documents(String run) {
        try {
            Path root = Path.of(required("BITACORA_SUPERVISOR_DOCS_ROOT")).toAbsolutePath().normalize();
            Path temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toRealPath();
            require(root.getFileName().toString().equals("bitacora-supervisor-it-" + run), "Nombre de directorio IT no permitido");
            require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "No existe el directorio exclusivo de documentos IT");
            for (Path p = root; p != null; p = p.getParent()) {
                require(!Files.isSymbolicLink(p), "No se permiten enlaces en el directorio IT");
            }
            require(root.toRealPath().getParent().equals(temp), "El directorio IT debe ser hijo directo del temporal de Java");
            Path marker = root.resolve(MARKER);
            require(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS), "Falta la marca de propiedad del directorio IT");
            require(Files.readString(marker, StandardCharsets.UTF_8).equals(run), "Marca de propiedad IT incorrecta");
            return root;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("No se pudo validar el directorio exclusivo IT", ex);
        }
    }

    static Connection open(Config config) throws SQLException {
        // config() valida AMBAS URLs antes de llegar aqui.
        Connection connection = DriverManager.getConnection(URL, config.user, config.password);
        try {
            verifyServer(connection);
            return connection;
        } catch (RuntimeException | SQLException ex) {
            connection.close();
            throw ex;
        }
    }

    static String normalizedPath(String value) {
        String result = value.replace('\\', '/');
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result.toLowerCase(Locale.ROOT);
    }

    static void verifyServer(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DATABASE(), @@port, VERSION(), @@datadir")) {
            require(result.next(), "El servidor no devolvio su identidad");
            require(SCHEMA.equals(result.getString(1)), "ABORTADO: esquema distinto del clon");
            require(result.getInt(2) == 3308, "ABORTADO: puerto inesperado");
            String version = result.getString(3);
            require(version != null && version.startsWith("10.4.32-MariaDB"), "ABORTADO: version distinta de MariaDB 10.4.32");
            require(normalizedPath(DATADIR).equals(normalizedPath(result.getString(4))), "ABORTADO: datadir distinto de la instancia recuperada");
        }
    }

    static void verifyHistory(Connection connection, boolean requireTen) throws SQLException {
        Set<String> versions = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT version, success FROM flyway_schema_history WHERE type <> 'TABLE'")) {
            while (result.next()) {
                String version = result.getString(1);
                require(version != null && result.getBoolean(2), "Historial Flyway inesperado o fallido; no se reparara automaticamente");
                require(versions.add(version), "Version duplicada en el historial Flyway");
            }
        }
        Set<String> expected = new HashSet<>();
        for (int version = 1; version <= (requireTen ? 10 : 9); version++) expected.add(Integer.toString(version));
        require(versions.equals(expected), requireTen ? "Se requieren V1-V10 satisfactorias" : "Se requieren exclusivamente V1-V9 satisfactorias");
    }

    static void verifyTables(Connection connection, boolean withTen) throws SQLException {
        for (String table : TABLES) {
            if (!withTen && table.equals("revision_urgencias_eventos")) continue;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND TABLE_TYPE='BASE TABLE'")) {
                statement.setString(1, table);
                try (ResultSet result = statement.executeQuery()) {
                    require(result.next() && "InnoDB".equalsIgnoreCase(result.getString(1)), "Falta una tabla InnoDB requerida: " + table);
                    require(!result.next(), "Tabla ambigua");
                }
            }
        }
        for (String table : WRITTEN) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE=?")) {
                statement.setString(1, table);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    require(result.getLong(1) == 0, "Hay triggers no revisados en una tabla de fixtures: " + table);
                }
            }
        }
    }
}
