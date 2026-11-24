-- BORRADOR BO2-R2: SOLO REVISION. NO EJECUTAR NI COPIAR A db/migration.
-- Sustituye al borrador V11 anterior, que NO debe aplicarse junto con este.
-- Crea dos tablas vacias; no actualiza filas existentes ni bitacora_espacios.
-- No cambia cupos, horarios o estados de reservas. No envia correos.
-- Fechas *_utc suministradas por la aplicacion en UTC.
-- Los participantes son PERSONAS concretas vinculadas a la reserva, no grupos de roles.

CREATE TABLE bitacora_operativa_eventos (
 id BIGINT NOT NULL AUTO_INCREMENT,
 clave_operacion CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 firma_payload CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 solicitud_id BIGINT NOT NULL,
 actor_usuario_id BIGINT NOT NULL,
 solicitante_usuario_id BIGINT NOT NULL,
 capturista_usuario_id BIGINT NOT NULL,
 supervisor_responsable_usuario_id BIGINT NOT NULL,
 version_esperada BIGINT NOT NULL,
 revision BIGINT NOT NULL,
 accion VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 condiciones_espacio TEXT NOT NULL,
 materiales_preparados TEXT NOT NULL,
 recursos_externos_preparados TEXT NOT NULL,
 observaciones TEXT NULL,
 fecha_registro_utc DATETIME(6) NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uq_bo_evento_clave (clave_operacion),
 UNIQUE KEY uq_bo_evento_revision (solicitud_id,revision),
 KEY idx_bo_evento_actor (actor_usuario_id),
 KEY idx_bo_evento_solicitante (solicitante_usuario_id),
 KEY idx_bo_evento_capturista (capturista_usuario_id),
 KEY idx_bo_evento_supervisor (supervisor_responsable_usuario_id),
 CONSTRAINT fk_bo_evento_solicitud FOREIGN KEY (solicitud_id)
  REFERENCES solicitudes_espacio(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_bo_evento_actor FOREIGN KEY (actor_usuario_id)
  REFERENCES usuarios(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_bo_evento_solicitante FOREIGN KEY (solicitante_usuario_id)
  REFERENCES usuarios(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_bo_evento_capturista FOREIGN KEY (capturista_usuario_id)
  REFERENCES usuarios(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_bo_evento_supervisor FOREIGN KEY (supervisor_responsable_usuario_id)
  REFERENCES usuarios(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_bo_evento_revision CHECK (
  version_esperada >= 0 AND revision > version_esperada AND revision-version_esperada=1
 ),
 CONSTRAINT ck_bo_evento_accion CHECK (accion='ESPACIO_LISTO'),
 CONSTRAINT ck_bo_evento_actor CHECK (actor_usuario_id=supervisor_responsable_usuario_id),
 CONSTRAINT ck_bo_evento_clave CHECK (
  clave_operacion REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
 ),
 CONSTRAINT ck_bo_evento_firma CHECK (firma_payload REGEXP '^[0-9a-f]{64}$'),
 CONSTRAINT ck_bo_evento_condiciones CHECK (CHAR_LENGTH(TRIM(condiciones_espacio)) BETWEEN 1 AND 4000),
 CONSTRAINT ck_bo_evento_materiales CHECK (CHAR_LENGTH(TRIM(materiales_preparados)) BETWEEN 1 AND 4000),
 CONSTRAINT ck_bo_evento_externos CHECK (CHAR_LENGTH(TRIM(recursos_externos_preparados)) BETWEEN 1 AND 4000),
 CONSTRAINT ck_bo_evento_observaciones CHECK (observaciones IS NULL OR CHAR_LENGTH(observaciones)<=4000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE bitacora_operativa_avisos (
 id BIGINT NOT NULL AUTO_INCREMENT,
 evento_id BIGINT NOT NULL,
 destinatario_usuario_id BIGINT NOT NULL,
 canal VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 estado VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 titulo VARCHAR(160) NOT NULL,
 mensaje TEXT NOT NULL,
 correo_destino VARCHAR(254) NULL,
 identificador_mensaje CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 fecha_creacion_utc DATETIME(6) NOT NULL,
 fecha_lectura_utc DATETIME(6) NULL,
 fecha_envio_utc DATETIME(6) NULL,
 intentos INT NOT NULL DEFAULT 0,
 codigo_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uq_bo_aviso_evento_canal (evento_id,destinatario_usuario_id,canal),
 UNIQUE KEY uq_bo_aviso_mensaje (identificador_mensaje),
 KEY idx_bo_aviso_bandeja (destinatario_usuario_id,canal,estado,fecha_creacion_utc),
 KEY idx_bo_aviso_cola (canal,estado,fecha_creacion_utc),
 CONSTRAINT fk_bo_aviso_evento FOREIGN KEY (evento_id)
  REFERENCES bitacora_operativa_eventos(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT fk_bo_aviso_destinatario FOREIGN KEY (destinatario_usuario_id)
  REFERENCES usuarios(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 CONSTRAINT ck_bo_aviso_texto CHECK (
  CHAR_LENGTH(TRIM(titulo)) BETWEEN 1 AND 160 AND CHAR_LENGTH(TRIM(mensaje)) BETWEEN 1 AND 16000
 ),
 CONSTRAINT ck_bo_aviso_intentos CHECK (intentos>=0),
 CONSTRAINT ck_bo_aviso_canal_estado CHECK (
  (canal='INTERNO' AND estado IN ('DISPONIBLE','LEIDO')
   AND correo_destino IS NULL AND identificador_mensaje IS NULL
   AND fecha_envio_utc IS NULL AND intentos=0 AND codigo_error IS NULL)
  OR
  (canal='CORREO' AND estado IN ('DESHABILITADO','PENDIENTE','EN_PROCESO','ENVIADO','FALLIDO','INCIERTO')
   AND correo_destino IS NOT NULL AND CHAR_LENGTH(TRIM(correo_destino)) BETWEEN 3 AND 254
   AND identificador_mensaje IS NOT NULL
   AND identificador_mensaje REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
   AND fecha_lectura_utc IS NULL)
 ),
 CONSTRAINT ck_bo_aviso_lectura CHECK (
  (estado='LEIDO' AND fecha_lectura_utc IS NOT NULL AND fecha_lectura_utc>=fecha_creacion_utc)
  OR (estado<>'LEIDO' AND fecha_lectura_utc IS NULL)
 ),
 CONSTRAINT ck_bo_aviso_envio CHECK (
  (estado='ENVIADO' AND fecha_envio_utc IS NOT NULL AND fecha_envio_utc>=fecha_creacion_utc)
  OR (estado<>'ENVIADO' AND fecha_envio_utc IS NULL)
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- CONTRATO DE APLICACION PENDIENTE DE IMPLEMENTAR Y PROBAR:
-- 1. Bloquear reserva y validar APROBADA; roles/cuentas vigentes en BD, no solo sesion.
-- 2. Resolver solicitante=solicitudes_espacio.usuario_id y
--    capturista=solicitudes_espacio.registrado_por_usuario_id. No del formulario.
-- 3. El supervisor responsable se asignara expresamente en el flujo de preparacion.
--    No inferir que cualquier supervisor es participante. No existe reasignacion implicita.
-- 4. Primera operacion ESPACIO_LISTO: actor debe ser el supervisor responsable.
--    Futuras revisiones conservan participantes; cambios requieren flujo auditado aparte.
-- 5. Destinatarios de avisos: IDs unicos de solicitante y capturista. Si son iguales,
--    un solo aviso por canal. Supervisor autor ve su registro, sin autocorreo adicional.
-- 6. Evento+avisos en una transaccion; UUID/payload/actor garantizan replay idempotente.
-- 7. SQL no puede comprobar por CHECK que un destinatario pertenezca a otra fila:
--    el servicio debe verificar pertenencia para crear, leer y marcar avisos.
--    La FK sola NO garantiza privacidad entre participantes.
-- 8. Acceso al detalle de preparacion solo a participantes activos, con su rol vigente.
--    El listado general operativo de supervisores es distinto y no otorga ese acceso.
-- 9. SMTP deshabilitado en clon, sin trabajador activo. No enviar dentro de la transaccion.
--    Estado ambiguo=INCIERTO, sin reenvio automatico; SMTP no garantiza exactamente una vez.
-- 10. DESHABILITADO no pasa automaticamente a PENDIENTE al configurar correo.
-- 11. ESPACIO_LISTO no crea entrega fisica ni recepcion ni modifica horas reales.
