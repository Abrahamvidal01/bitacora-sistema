-- Indices para comprobar que solicitud e inventario
-- pertenecen al mismo espacio.

ALTER TABLE solicitudes_espacio
    ADD UNIQUE KEY uq_solicitud_id_espacio (
    id,
    espacio_id
    );

ALTER TABLE inventario_espacios
    ADD UNIQUE KEY uq_inventario_id_espacio (
    id,
    espacio_id
    );

-- Detalle historico de recursos seleccionados por reserva.
-- No descuenta permanentemente las existencias del inventario.

CREATE TABLE solicitud_recursos (
                                    id BIGINT NOT NULL AUTO_INCREMENT,

                                    solicitud_id BIGINT NOT NULL,
                                    inventario_id BIGINT NOT NULL,
                                    espacio_id BIGINT NOT NULL,

                                    nombre_item VARCHAR(150) NOT NULL,
                                    descripcion_item TEXT NULL,
                                    estado_bien VARCHAR(50) NOT NULL,

                                    cantidad_solicitada INT NOT NULL,
                                    existencias_al_confirmar INT NOT NULL,

                                    fecha_registro DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                    PRIMARY KEY (id),

    -- Un registro de inventario no se repite dentro de la solicitud.
                                    UNIQUE KEY uq_solicitud_recurso_inventario (
                                        solicitud_id,
                                        inventario_id
                                        ),

                                    KEY idx_recurso_solicitud_espacio (
        solicitud_id,
        espacio_id
    ),

                                    KEY idx_recurso_inventario_espacio (
        inventario_id,
        espacio_id
    ),

                                    CONSTRAINT fk_detalle_recurso_solicitud
                                        FOREIGN KEY (
                                                     solicitud_id,
                                                     espacio_id
                                            )
                                            REFERENCES solicitudes_espacio (
                                                                            id,
                                                                            espacio_id
                                                )
                                            ON DELETE RESTRICT
                                            ON UPDATE RESTRICT,

                                    CONSTRAINT fk_detalle_recurso_inventario
                                        FOREIGN KEY (
                                                     inventario_id,
                                                     espacio_id
                                            )
                                            REFERENCES inventario_espacios (
                                                                            id,
                                                                            espacio_id
                                                )
                                            ON DELETE RESTRICT
                                            ON UPDATE RESTRICT,

                                    CONSTRAINT chk_detalle_recurso_nombre
                                        CHECK (
                                            CHAR_LENGTH(TRIM(nombre_item)) > 0
                                            ),

                                    CONSTRAINT chk_detalle_recurso_estado
                                        CHECK (
                                            estado_bien IN ('BUENO', 'REGULAR')
                                            ),

                                    CONSTRAINT chk_detalle_recurso_cantidad
                                        CHECK (
                                            cantidad_solicitada > 0
                                                AND existencias_al_confirmar >= cantidad_solicitada
                                            )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;