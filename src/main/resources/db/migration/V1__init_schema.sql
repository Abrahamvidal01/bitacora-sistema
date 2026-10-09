-- phpMyAdmin SQL Dump
-- version 5.2.1
-- https://www.phpmyadmin.net/
--
-- Servidor: 127.0.0.1
-- Tiempo de generación: 08-10-2026 a las 17:38:52
-- Versión del servidor: 10.4.32-MariaDB
-- Versión de PHP: 8.2.12

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

--
    -- Base de datos: `bitacora_espacios`
--

    -- --------------------------------------------------------

        --
            -- Estructura de tabla para la tabla `bitacora_espacios`
--

CREATE TABLE `bitacora_espacios` (
                                     `id` bigint(20) NOT NULL,
                                     `solicitud_id` bigint(20) NOT NULL,
                                     `hora_entrada_real` datetime DEFAULT NULL,
                                     `observaciones_entrega` text DEFAULT NULL,
                                     `hora_salida_real` datetime DEFAULT NULL,
                                     `observaciones_recepcion` text DEFAULT NULL,
                                     `hora_limpieza_inicio` datetime DEFAULT NULL,
                                     `hora_limpieza_fin` datetime DEFAULT NULL,
                                     `tiene_incidencia` tinyint(1) DEFAULT 0,
                                     `detalle_incidencia` text DEFAULT NULL,
                                     `estatus_bitacora` varchar(30) DEFAULT 'PENDIENTE_ENTREGA',
                                     `registrado_por_usuario_id` bigint(20) NOT NULL,
                                     `fecha_registro` datetime DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `departamentos`
--

CREATE TABLE `departamentos` (
                                 `id` bigint(20) NOT NULL,
                                 `nombre_departamento` varchar(100) NOT NULL,
                                 `clave_departamento` varchar(20) DEFAULT NULL,
                                 `activo` tinyint(1) DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

--
-- Volcado de datos para la tabla `departamentos`
--

INSERT INTO `departamentos` (`id`, `nombre_departamento`, `clave_departamento`, `activo`) VALUES
    (1, 'Recursos Materiales y Servicios', 'RMS-01', 1);

-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `dictamenes`
--

CREATE TABLE `dictamenes` (
                              `id` bigint(20) NOT NULL,
                              `solicitud_id` bigint(20) NOT NULL,
                              `autorizado_por_usuario_id` bigint(20) NOT NULL,
                              `estatus_dictamen` varchar(20) NOT NULL,
                              `observaciones_dictamen` text DEFAULT NULL,
                              `fecha_dictamen` datetime DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `espacios`
--

CREATE TABLE `espacios` (
                            `id` bigint(20) NOT NULL,
                            `nombre_espacio` varchar(100) NOT NULL,
                            `ubicacion` varchar(150) NOT NULL,
                            `capacidad` int(11) NOT NULL,
                            `estado_actual` varchar(30) DEFAULT 'DISPONIBLE',
                            `recursos_base_json` text DEFAULT NULL,
                            `activo` tinyint(1) DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `historial_usuarios`
--

CREATE TABLE `historial_usuarios` (
                                      `id` bigint(20) NOT NULL,
                                      `usuario_afectado_id` bigint(20) NOT NULL,
                                      `realizado_por_usuario_id` bigint(20) NOT NULL,
                                      `tipo_accion` varchar(50) NOT NULL,
                                      `descripcion_detalle` text NOT NULL,
                                      `fecha_evento` datetime DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `roles`
--

CREATE TABLE `roles` (
                         `id` bigint(20) NOT NULL,
                         `nombre_rol` varchar(50) NOT NULL,
                         `descripcion` varchar(255) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

--
-- Volcado de datos para la tabla `roles`
--

INSERT INTO `roles` (`id`, `nombre_rol`, `descripcion`) VALUES
                                                            (1, 'ADMINISTRADOR', 'Acceso total y configuración del sistema'),
                                                            (2, 'SUPERVISOR', 'Dictamina y aprueba solicitudes de espacios'),
                                                            (3, 'SOLICITANTE', 'Crea solicitudes de reserva de espacios');

-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `solicitudes_espacio`
--

CREATE TABLE `solicitudes_espacio` (
                                       `id` bigint(20) NOT NULL,
                                       `folio` varchar(30) NOT NULL,
                                       `usuario_id` bigint(20) NOT NULL,
                                       `espacio_id` bigint(20) NOT NULL,
                                       `fecha_evento` date NOT NULL,
                                       `hora_inicio` time NOT NULL,
                                       `hora_fin` time NOT NULL,
                                       `motivo_evento` varchar(255) NOT NULL,
                                       `recursos_externos` text DEFAULT NULL,
                                       `ruta_oficio_requisicion` varchar(255) DEFAULT NULL,
                                       `es_urgente_sin_oficio` tinyint(1) DEFAULT 0,
                                       `estatus_solicitud` varchar(30) DEFAULT 'PENDIENTE',
                                       `tipo_origen` varchar(30) DEFAULT 'SOLICITUD_DOCENTE',
                                       `fecha_solicitud` datetime DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `usuarios`
--

CREATE TABLE `usuarios` (
                            `id` bigint(20) NOT NULL,
                            `nombre_completo` varchar(150) NOT NULL,
                            `correo_institucional` varchar(100) NOT NULL,
                            `password_hash` varchar(255) NOT NULL,
                            `departamento_id` bigint(20) NOT NULL,
                            `activo` tinyint(1) DEFAULT 1,
                            `fecha_creacion` datetime DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

--
-- Volcado de datos para la tabla `usuarios`
--

-- Estructura de tabla para la tabla `usuario_roles`
--

CREATE TABLE `usuario_roles` (
                                 `usuario_id` bigint(20) NOT NULL,
                                 `rol_id` bigint(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

--
-- Volcado de datos para la tabla `usuario_roles`
--


--
-- Índices para tablas volcadas
--

--
-- Indices de la tabla `bitacora_espacios`
--
ALTER TABLE `bitacora_espacios`
    ADD PRIMARY KEY (`id`),
    ADD UNIQUE KEY `solicitud_id` (`solicitud_id`),
    ADD KEY `fk_bitacora_usuario` (`registrado_por_usuario_id`);

--
-- Indices de la tabla `departamentos`
--
ALTER TABLE `departamentos`
    ADD PRIMARY KEY (`id`),
    ADD UNIQUE KEY `nombre_departamento` (`nombre_departamento`);

--
-- Indices de la tabla `dictamenes`
--
ALTER TABLE `dictamenes`
    ADD PRIMARY KEY (`id`),
    ADD UNIQUE KEY `solicitud_id` (`solicitud_id`),
    ADD KEY `fk_dictamen_usuario` (`autorizado_por_usuario_id`);

--
-- Indices de la tabla `espacios`
--
ALTER TABLE `espacios`
    ADD PRIMARY KEY (`id`),
    ADD UNIQUE KEY `nombre_espacio` (`nombre_espacio`);

--
-- Indices de la tabla `historial_usuarios`
--
ALTER TABLE `historial_usuarios`
    ADD PRIMARY KEY (`id`),
    ADD KEY `fk_log_usuario_afectado` (`usuario_afectado_id`),
    ADD KEY `fk_log_usuario_autor` (`realizado_por_usuario_id`);

--
-- Indices de la tabla `roles`
--
ALTER TABLE `roles`
    ADD PRIMARY KEY (`id`),
    ADD UNIQUE KEY `nombre_rol` (`nombre_rol`);

--
-- Indices de la tabla `solicitudes_espacio`
--
ALTER TABLE `solicitudes_espacio`
    ADD PRIMARY KEY (`id`),
    ADD UNIQUE KEY `folio` (`folio`),
    ADD KEY `fk_solicitud_usuario` (`usuario_id`),
    ADD KEY `fk_solicitud_espacio` (`espacio_id`);

--
-- Indices de la tabla `usuarios`
--
ALTER TABLE `usuarios`
    ADD PRIMARY KEY (`id`),
    ADD UNIQUE KEY `correo_institucional` (`correo_institucional`),
    ADD KEY `fk_usuario_depto` (`departamento_id`);

--
-- Indices de la tabla `usuario_roles`
--
ALTER TABLE `usuario_roles`
    ADD PRIMARY KEY (`usuario_id`,`rol_id`),
    ADD KEY `FKbt9i9yrb9ug88xnh82n9m60pr` (`rol_id`);

--
-- AUTO_INCREMENT de las tablas volcadas
--

--
-- AUTO_INCREMENT de la tabla `bitacora_espacios`
--
ALTER TABLE `bitacora_espacios`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- AUTO_INCREMENT de la tabla `departamentos`
--
ALTER TABLE `departamentos`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=2;

--
-- AUTO_INCREMENT de la tabla `dictamenes`
--
ALTER TABLE `dictamenes`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- AUTO_INCREMENT de la tabla `espacios`
--
ALTER TABLE `espacios`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- AUTO_INCREMENT de la tabla `historial_usuarios`
--
ALTER TABLE `historial_usuarios`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- AUTO_INCREMENT de la tabla `roles`
--
ALTER TABLE `roles`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=4;

--
-- AUTO_INCREMENT de la tabla `solicitudes_espacio`
--
ALTER TABLE `solicitudes_espacio`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

--
-- AUTO_INCREMENT de la tabla `usuarios`
--
ALTER TABLE `usuarios`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=2;

--
-- Restricciones para tablas volcadas
--

--
-- Filtros para la tabla `bitacora_espacios`
--
ALTER TABLE `bitacora_espacios`
    ADD CONSTRAINT `fk_bitacora_solicitud` FOREIGN KEY (`solicitud_id`) REFERENCES `solicitudes_espacio` (`id`),
    ADD CONSTRAINT `fk_bitacora_usuario` FOREIGN KEY (`registrado_por_usuario_id`) REFERENCES `usuarios` (`id`);

--
-- Filtros para la tabla `dictamenes`
--
ALTER TABLE `dictamenes`
    ADD CONSTRAINT `fk_dictamen_solicitud` FOREIGN KEY (`solicitud_id`) REFERENCES `solicitudes_espacio` (`id`),
    ADD CONSTRAINT `fk_dictamen_usuario` FOREIGN KEY (`autorizado_por_usuario_id`) REFERENCES `usuarios` (`id`);

--
-- Filtros para la tabla `historial_usuarios`
--
ALTER TABLE `historial_usuarios`
    ADD CONSTRAINT `fk_log_usuario_afectado` FOREIGN KEY (`usuario_afectado_id`) REFERENCES `usuarios` (`id`),
    ADD CONSTRAINT `fk_log_usuario_autor` FOREIGN KEY (`realizado_por_usuario_id`) REFERENCES `usuarios` (`id`);

--
-- Filtros para la tabla `solicitudes_espacio`
--
ALTER TABLE `solicitudes_espacio`
    ADD CONSTRAINT `fk_solicitud_espacio` FOREIGN KEY (`espacio_id`) REFERENCES `espacios` (`id`),
    ADD CONSTRAINT `fk_solicitud_usuario` FOREIGN KEY (`usuario_id`) REFERENCES `usuarios` (`id`);

--
-- Filtros para la tabla `usuarios`
--
ALTER TABLE `usuarios`
    ADD CONSTRAINT `fk_usuario_depto` FOREIGN KEY (`departamento_id`) REFERENCES `departamentos` (`id`);

--
-- Filtros para la tabla `usuario_roles`
--
ALTER TABLE `usuario_roles`
    ADD CONSTRAINT `FKbt9i9yrb9ug88xnh82n9m60pr` FOREIGN KEY (`rol_id`) REFERENCES `roles` (`id`),
    ADD CONSTRAINT `FKuu9tea04xb29m2km5lwe46ua` FOREIGN KEY (`usuario_id`) REFERENCES `usuarios` (`id`);
COMMIT;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
