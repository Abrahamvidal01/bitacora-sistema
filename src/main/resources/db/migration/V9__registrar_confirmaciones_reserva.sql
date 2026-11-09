-- Metadatos de confirmación e idempotencia.
-- Migración nueva: no modificar V1 a V8 ya aplicadas.
-- No altera ni completa arbitrariamente solicitudes anteriores.

CREATE TABLE confirmaciones_reserva (
                                        clave_confirmacion CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                                        solicitud_id BIGINT NOT NULL,
                                        registrado_por_usuario_id BIGINT NOT NULL,
                                        firma_solicitud CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                                        modalidad VARCHAR(30) NOT NULL,
                                        motivo_urgencia VARCHAR(255) NULL,
                                        fecha_registro DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                        PRIMARY KEY (clave_confirmacion),
                                        UNIQUE KEY uq_confirmacion_solicitud (solicitud_id),
                                        KEY idx_confirmacion_registrado_por (registrado_por_usuario_id),

                                        CONSTRAINT fk_confirmacion_solicitud
                                            FOREIGN KEY (solicitud_id) REFERENCES solicitudes_espacio (id)
                                                ON DELETE RESTRICT ON UPDATE RESTRICT,
                                        CONSTRAINT fk_confirmacion_registrado_por
                                            FOREIGN KEY (registrado_por_usuario_id) REFERENCES usuarios (id)
                                                ON DELETE RESTRICT ON UPDATE RESTRICT,
                                        CONSTRAINT chk_confirmacion_modalidad
                                            CHECK (modalidad IN (
                                                                 'NORMAL', 'URGENTE_INVENTARIO', 'URGENTE_REQUISICION'
                                                )),
                                        CONSTRAINT chk_confirmacion_motivo
                                            CHECK (
                                                (modalidad = 'NORMAL' AND motivo_urgencia IS NULL)
                                                    OR
                                                (modalidad IN ('URGENTE_INVENTARIO', 'URGENTE_REQUISICION')
                                                    AND motivo_urgencia IS NOT NULL
                                                    AND CHAR_LENGTH(TRIM(motivo_urgencia)) > 0)
                                                )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;