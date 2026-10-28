ALTER TABLE solicitudes_espacio
    ADD COLUMN ruta_oficio_reservacion VARCHAR(255) NULL DEFAULT NULL,
    ADD COLUMN ruta_requisicion VARCHAR(255) NULL DEFAULT NULL;