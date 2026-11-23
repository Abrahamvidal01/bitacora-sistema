package com.sistema.bitacora.supervisorrevision;

import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;
import org.slf4j.LoggerFactory;

/** Solo test. LOG_GUARD_R2. No conexiones ni secretos; bloquear ANTES del DataSource/EMF. */
final class SupervisorItLogGuard {
    private static final String OBSERVED = "org.hibernate.orm.connections.pooling";
    private static final String[] DISABLED = {
            "org.hibernate", OBSERVED, "org.hibernate.orm.jdbc.env",
            "org.mariadb.jdbc", "org.springframework.jdbc", "org.springframework.orm.jpa",
            "org.springframework.boot.autoconfigure.jdbc", "com.zaxxer.hikari"
    };
    private static LoggingSystem logging;
    private static ClassLoader loader;
    private SupervisorItLogGuard() { }

    static synchronized void aplicar(ConfigurableApplicationContext context) {
        loader = context.getClassLoader();
        if (loader == null) loader = SupervisorItLogGuard.class.getClassLoader();
        logging = LoggingSystem.get(loader);
        // Las propiedades puestas por un ApplicationContextInitializer pueden llegar DESPUES
        // de la inicializacion del logging. Cambiar el sistema real, no solo el Environment.
        logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.WARN);
        for (String name : DISABLED) {
            logging.setLogLevel(name, LogLevel.OFF);
            java.util.logging.Logger.getLogger(name).setLevel(java.util.logging.Level.OFF);
        }
        comprobar();
    }

    static synchronized void comprobar() {
        if (logging == null) throw new IllegalStateException("Guarda de logs IT ausente; no crear DataSource");
        for (String name : DISABLED) {
            LoggerConfiguration configuration = logging.getLoggerConfiguration(name);
            if (configuration == null || configuration.getEffectiveLevel() != LogLevel.OFF)
                throw new IllegalStateException("No se pudo bloquear logging JDBC/ORM; no crear DataSource");
        }
        var facade = LoggerFactory.getLogger(OBSERVED);
        if (facade.isTraceEnabled() || facade.isDebugEnabled() || facade.isInfoEnabled()
                || facade.isWarnEnabled() || facade.isErrorEnabled())
            throw new IllegalStateException("El logger observado sigue activo; no crear DataSource");
        // Hibernate usa JBoss Logging: verificar tambien SU fachada real, sin dependencia nueva.
        try {
            Class<?> type = Class.forName("org.jboss.logging.Logger", true, loader);
            Object logger = type.getMethod("getLogger", String.class).invoke(null, OBSERVED);
            for (String method : new String[]{"isInfoEnabled", "isDebugEnabled", "isTraceEnabled"}) {
                if (Boolean.TRUE.equals(type.getMethod(method).invoke(logger)))
                    throw new IllegalStateException("La fachada ORM mantiene logging sensible activo");
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            // No anexar causas/configuracion/credenciales a esta excepcion de preflight.
            throw new IllegalStateException("No se pudo comprobar la fachada ORM; detener antes del DataSource");
        }
    }
}
