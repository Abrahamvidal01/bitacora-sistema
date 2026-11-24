package com.sistema.bitacora.supervisorrevision;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** Preparacion explicita, separada: NO inicia Spring ni ejecuta las pruebas funcionales. */
@EnabledIfEnvironmentVariable(named="BITACORA_SUPERVISOR_PREPARAR_V10", matches="SI")
class PrepararSupervisorClonTest {
    @Test
    void aplicarExclusivamenteV10AlClonVerificado() throws Exception {
        SupervisorItSupport.require("APLICAR V10 SOLO CLON".equals(System.getenv("BITACORA_SUPERVISOR_CONFIRMACION_V10")),
                "No se autorizo explicitamente la preparacion del clon");
        var config = SupervisorItSupport.config(false);
        boolean already;
        try (Connection connection = SupervisorItSupport.open(config)) {
            SupervisorItSupport.verifyTables(connection, false);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version='10' AND success=1")) {
                result.next(); already = result.getLong(1) == 1;
            }
            SupervisorItSupport.verifyHistory(connection, already);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='revision_urgencias_eventos'")) {
                result.next();
                assertEquals(already ? 1 : 0, result.getInt(1), "Tabla V10 e historial no coinciden: revisar manualmente; no reparar ni borrar");
            }
        }
        // No usa FlywayAutoConfiguration, clean(), repair(), baseline() ni migraciones de otra ubicacion.
        Flyway flyway = Flyway.configure()
                .dataSource(SupervisorItSupport.URL, config.user, config.password)
                .locations("classpath:db/migration", "classpath:db/supervisor")
                .target("10")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .ignoreMigrationPatterns(already
                        ? new String[0]
                        : new String[]{"*:pending"})
                .load();
        flyway.validate();
        MigrationInfo[] pending = flyway.info().pending();
        if (already) {
            assertEquals(0, pending.length, "Hay migraciones adicionales inesperadas");
        } else {
            assertEquals(1, pending.length, "Solo V10 puede estar pendiente");
            assertNotNull(pending[0].getVersion());
            assertEquals("10", pending[0].getVersion().getVersion());
            assertEquals("V10__revision_urgencias.sql", pending[0].getScript());
            assertEquals(1, flyway.migrate().migrationsExecuted, "La preparacion debe aplicar exclusivamente V10");
        }
        try (Connection connection = SupervisorItSupport.open(config)) {
            SupervisorItSupport.verifyHistory(connection, true);
            SupervisorItSupport.verifyTables(connection, true);
        }
    }
}
