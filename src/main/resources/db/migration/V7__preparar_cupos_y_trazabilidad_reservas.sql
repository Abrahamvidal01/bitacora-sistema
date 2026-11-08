-- Preparada para una base sin solicitudes existentes.
-- Los departamentos actuales reciben cupo 1.
-- Direccion se configurara explicitamente cuando exista:
-- no se identifica mediante coincidencias en su nombre.

ALTER TABLE departamentos
    ADD COLUMN cupo_mensual TINYINT UNSIGNED NOT NULL DEFAULT 1,
    ADD CONSTRAINT chk_departamento_cupo_mensual
        CHECK (cupo_mensual IN (1, 4));

-- Permite comprobar que departamento y usuario pertenecen
-- al nombramiento indicado en la solicitud.

ALTER TABLE nombramientos_departamento
    ADD UNIQUE KEY uq_nombramiento_identidad (
    id,
    departamento_id,
    usuario_id
    );

-- Conserva el contexto de la solicitud.
-- No se deduce posteriormente del departamento actual del usuario.

ALTER TABLE solicitudes_espacio
    ADD COLUMN departamento_solicitante_id BIGINT NOT NULL,
    ADD COLUMN nombramiento_id BIGINT NOT NULL,
    ADD COLUMN registrado_por_usuario_id BIGINT NOT NULL,

    ADD KEY idx_solicitud_departamento_fecha (
        departamento_solicitante_id,
        fecha_evento
    ),

    ADD KEY idx_solicitud_nombramiento_identidad (
        nombramiento_id,
        departamento_solicitante_id,
        usuario_id
    ),

    ADD KEY idx_solicitud_registrado_por (
        registrado_por_usuario_id
    ),

    ADD CONSTRAINT fk_solicitud_departamento_solicitante
        FOREIGN KEY (departamento_solicitante_id)
        REFERENCES departamentos (id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

                                                                                           ADD CONSTRAINT fk_solicitud_nombramiento_identidad
                                                                                           FOREIGN KEY (
                                                                                           nombramiento_id,
                                                                                           departamento_solicitante_id,
                                                                                           usuario_id
                                                                                           )
                                                                                           REFERENCES nombramientos_departamento (
                                                                                           id,
                                                                                           departamento_id,
                                                                                           usuario_id
                                                                                           )
           ON DELETE RESTRICT
        ON UPDATE RESTRICT,

                                    ADD CONSTRAINT fk_solicitud_registrado_por
                                    FOREIGN KEY (registrado_por_usuario_id)
                                    REFERENCES usuarios (id)
           ON DELETE RESTRICT
        ON UPDATE RESTRICT;

-- Un consumo por solicitud.
-- El departamento y el mes se obtendran de la solicitud:
-- departamento_solicitante_id y fecha_evento.
--
-- Cambiar el estado a CANCELADA no modifica este registro.
-- Cualquier reintegro excepcional requerira usuario, fecha y motivo.

CREATE TABLE consumo_cupos_reserva (
                                       solicitud_id BIGINT NOT NULL,

                                       fecha_consumo DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                       fecha_reintegro DATETIME NULL,
                                       reintegrado_por_usuario_id BIGINT NULL,
                                       motivo_reintegro VARCHAR(255) NULL,

                                       PRIMARY KEY (solicitud_id),

                                       KEY idx_consumo_reintegrado_por (
        reintegrado_por_usuario_id
    ),

                                       CONSTRAINT fk_consumo_cupo_solicitud
                                           FOREIGN KEY (solicitud_id)
                                               REFERENCES solicitudes_espacio (id)
                                               ON DELETE RESTRICT
                                               ON UPDATE RESTRICT,

                                       CONSTRAINT fk_consumo_cupo_reintegrado_por
                                           FOREIGN KEY (reintegrado_por_usuario_id)
                                               REFERENCES usuarios (id)
                                               ON DELETE RESTRICT
                                               ON UPDATE RESTRICT,

                                       CONSTRAINT chk_consumo_cupo_reintegro
                                           CHECK (
                                               (
                                                   fecha_reintegro IS NULL
                                                       AND reintegrado_por_usuario_id IS NULL
                                                       AND motivo_reintegro IS NULL
                                                   )
                                                   OR
                                               (
                                                   fecha_reintegro IS NOT NULL
                                                       AND reintegrado_por_usuario_id IS NOT NULL
                                                       AND motivo_reintegro IS NOT NULL
                                                       AND CHAR_LENGTH(TRIM(motivo_reintegro)) > 0
                                                       AND fecha_reintegro >= fecha_consumo
                                                   )
                                               )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;