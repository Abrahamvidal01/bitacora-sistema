--
-- Estructura de tabla para la tabla `inventario_espacios`
--

CREATE TABLE IF NOT EXISTS `inventario_espacios` (
                                                     `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `nombre_item` varchar(150) NOT NULL,
    `descripcion` text DEFAULT NULL,
    `cantidad` int(11) NOT NULL DEFAULT 1,
    `estado_bien` varchar(50) DEFAULT 'BUENO',
    `espacio_id` bigint(20) NOT NULL,
    `fecha_registro` timestamp DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `fk_inventario_espacio` (`espacio_id`),
    CONSTRAINT `fk_inventario_espacio` FOREIGN KEY (`espacio_id`) REFERENCES `espacios` (`id`) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;