CREATE TABLE contador_folios_reserva (
                                         id TINYINT NOT NULL,
                                         ultimo_consecutivo BIGINT NOT NULL DEFAULT 0,
                                         PRIMARY KEY (id),
                                         CONSTRAINT chk_contador_folios_id CHECK (id = 1),
                                         CONSTRAINT chk_contador_folios_valor CHECK (ultimo_consecutivo >= 0)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci;

INSERT INTO contador_folios_reserva (id, ultimo_consecutivo)
SELECT
    1,
    COALESCE(
            MAX(CAST(SUBSTRING(folio, 10) AS UNSIGNED)),
            0
    )
FROM solicitudes_espacio
WHERE folio REGEXP '^RES-[0-9]{4}-[0-9]{6,}$';