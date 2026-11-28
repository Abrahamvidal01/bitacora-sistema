-- V13 propuesta, pendiente de revision y autorizacion local explicita.
-- Conservar FUERA de src/main/resources y de las ubicaciones automaticas de Flyway.
-- Solo tabla NUEVA; no ALTER, semillas, cambios de departamentos ni de cupos existentes.
-- FK sobre departamentos(id) y usuarios(id), BIGINT con signo e InnoDB.
-- No ejecutar manualmente ni reintentar si ya existe la tabla o la version 13.
-- Las acciones futuras solo se enumeran; el servicio actual implementa ALTA_DEPARTAMENTO.
CREATE TABLE historial_departamentos (
    id BIGINT NOT NULL AUTO_INCREMENT,
    departamento_id BIGINT NOT NULL,
    realizado_por_usuario_id BIGINT NOT NULL,
    tipo_accion VARCHAR(32) NOT NULL,
    nombre_anterior VARCHAR(100) NULL,
    nombre_nuevo VARCHAR(100) NOT NULL,
    clave_anterior VARCHAR(20) NULL,
    clave_nueva VARCHAR(20) NULL,
    activo_anterior TINYINT NULL,
    activo_nuevo TINYINT NOT NULL,
    cupo_anterior TINYINT UNSIGNED NULL,
    cupo_nuevo TINYINT UNSIGNED NOT NULL,
    fecha_evento_utc DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_histdep_departamento_id (departamento_id, id),
    KEY ix_histdep_usuario_id (realizado_por_usuario_id, id),
    CONSTRAINT fk_histdep_departamento FOREIGN KEY (departamento_id)
        REFERENCES departamentos (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_histdep_usuario FOREIGN KEY (realizado_por_usuario_id)
        REFERENCES usuarios (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_histdep_accion CHECK (tipo_accion IN
        ('ALTA_DEPARTAMENTO', 'EDICION_DEPARTAMENTO',
         'ACTIVACION_DEPARTAMENTO', 'DESACTIVACION_DEPARTAMENTO')),
    CONSTRAINT ck_histdep_nombre CHECK (CHAR_LENGTH(TRIM(nombre_nuevo)) > 0),
    CONSTRAINT ck_histdep_activo_ant CHECK (activo_anterior IS NULL OR activo_anterior IN (0, 1)),
    CONSTRAINT ck_histdep_activo_nuevo CHECK (activo_nuevo IN (0, 1)),
    CONSTRAINT ck_histdep_cupo_ant CHECK (cupo_anterior IS NULL OR cupo_anterior IN (1, 4)),
    CONSTRAINT ck_histdep_cupo_nuevo CHECK (cupo_nuevo IN (1, 4))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- UTC es un contrato de escritura, no una conversion de DATETIME por MariaDB.
-- El servicio escribe LocalDateTime UTC mediante JDBC 4.2, sin Timestamp.
-- Comprobar ida/vuelta y precision con JVM/sesion no UTC en integracion real.
-- Sin idempotency key: esta tabla NO garantiza una sola ejecucion ante reintentos.
