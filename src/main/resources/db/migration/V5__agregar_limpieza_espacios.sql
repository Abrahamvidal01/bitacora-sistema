ALTER TABLE espacios
    ADD COLUMN minutos_limpieza INT NOT NULL DEFAULT 30,
    ADD CONSTRAINT chk_espacios_minutos_limpieza
        CHECK (minutos_limpieza > 0);