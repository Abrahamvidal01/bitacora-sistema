CREATE TABLE nombramientos_departamento (
    id BIGINT NOT NULL AUTO_INCREMENT,

    departamento_id BIGINT NOT NULL,
    usuario_id BIGINT NOT NULL,

    fecha_inicio DATETIME NOT NULL,
    fecha_fin DATETIME NULL DEFAULT NULL,

    registrado_por_usuario_id BIGINT NOT NULL,
    finalizado_por_usuario_id BIGINT NULL DEFAULT NULL,

    motivo_fin VARCHAR(255) NULL DEFAULT NULL,
    fecha_registro DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Identifica la unidad solo mientras el nombramiento esté abierto.
    departamento_vigente_id BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN fecha_fin IS NULL THEN departamento_id
                ELSE NULL
            END
        ) PERSISTENT,

    PRIMARY KEY (id),

    UNIQUE KEY uq_nombramiento_departamento_vigente (
        departamento_vigente_id
    ),

    KEY idx_nombramiento_departamento_inicio (
        departamento_id,
        fecha_inicio
    ),

    KEY idx_nombramiento_usuario (usuario_id),

    CONSTRAINT fk_nombramiento_departamento
        FOREIGN KEY (departamento_id)
        REFERENCES departamentos(id),

    CONSTRAINT fk_nombramiento_usuario
        FOREIGN KEY (usuario_id)
        REFERENCES usuarios(id),

    CONSTRAINT fk_nombramiento_registrado_por
        FOREIGN KEY (registrado_por_usuario_id)
        REFERENCES usuarios(id),

    CONSTRAINT fk_nombramiento_finalizado_por
        FOREIGN KEY (finalizado_por_usuario_id)
        REFERENCES usuarios(id),

    CONSTRAINT chk_nombramiento_fechas
        CHECK (
            fecha_fin IS NULL
            OR fecha_fin >= fecha_inicio
        )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;