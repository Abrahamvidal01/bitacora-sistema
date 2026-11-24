-- BORRADOR BO3 PARA REVISION. NO EJECUTAR MANUALMENTE NI COPIAR A db/migration.
-- Requiere V11 R2 aplicada. No modificar V11 ya registrada por Flyway.
-- Esta asignacion representa responsabilidad operativa, NO aprobacion del evento.
-- No elige personas por rol global ni cambia las reservas existentes.
-- Cuenta concreta que toma la preparacion = supervisor_usuario_id.
-- Sin endpoint de borrado, liberacion ni reasignacion en esta primera entrega.

CREATE TABLE bitacora_operativa_asignaciones (
    solicitud_id BIGINT NOT NULL,
    supervisor_usuario_id BIGINT NOT NULL,
    clave_operacion CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    firma_payload CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fecha_asignacion_utc DATETIME(6) NOT NULL,
    PRIMARY KEY (solicitud_id),
    UNIQUE KEY uq_bo_asignacion_clave (clave_operacion),
    UNIQUE KEY uq_bo_asignacion_responsable (solicitud_id,supervisor_usuario_id),
    KEY idx_bo_asignacion_supervisor (supervisor_usuario_id,fecha_asignacion_utc),
    CONSTRAINT fk_bo_asignacion_solicitud FOREIGN KEY (solicitud_id)
        REFERENCES solicitudes_espacio(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_bo_asignacion_supervisor FOREIGN KEY (supervisor_usuario_id)
        REFERENCES usuarios(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_bo_asignacion_clave CHECK (
        clave_operacion REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_bo_asignacion_firma CHECK (
        firma_payload REGEXP '^[0-9a-f]{64}$'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Refuerzo relacional: un evento ESPACIO_LISTO debe corresponder al responsable
-- asignado de esa misma reserva. No implica que el rol/cuenta continuen vigentes:
-- esa verificacion tambien es obligatoria en el servicio en cada operacion.
ALTER TABLE bitacora_operativa_eventos
    ADD CONSTRAINT fk_bo_evento_asignacion
    FOREIGN KEY (solicitud_id,supervisor_responsable_usuario_id)
    REFERENCES bitacora_operativa_asignaciones(solicitud_id,supervisor_usuario_id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

-- CONTRATO DE SERVICIO/INTERFAZ PENDIENTE DE IMPLEMENTAR Y PROBAR:
-- * POST /supervisor/api/bitacora-operativa/{id}/tomar con sesion, CSRF y UUID.
-- * Obtener actor desde autenticacion; comprobar usuario activo y rol SUPERVISOR
--   actual en BD. Nunca admitir supervisor_usuario_id enviado por el navegador.
-- * Bloquear la solicitud FOR UPDATE; comprobar APROBADA en esa transaccion.
-- * Si no hay asignacion: insertar responsable, UUID, firma y fecha UTC.
-- * UUID+actor+solicitud/payload iguales: devolver asignacion original, sin duplicar.
-- * UUID reutilizado por otra persona/reserva: conflicto sin cambios.
-- * Otra persona ya es responsable: conflicto; nunca UPDATE para apropiarsela.
-- * Responsable anterior con cuenta deshabilitada: no habilitar toma automatica.
--   Una reasignacion futura necesitara autorizacion, motivo e historial separado.
-- * Dos tomas concurrentes: una sola asignacion persistida.
-- * Separar lista minima para asignacion de detalles/avisos privados.
--   Tener rol SUPERVISOR no autoriza leer las condiciones privadas de otra
--   preparacion. Detalles solo a solicitante, capturista y supervisor vinculados,
--   todos con cuenta activa y rol requerido vigente para su funcion.
-- * Las comprobaciones deben hacerse en backend, incluso con URL/ID directo.
-- * En esta primera version tomar no envia correo ni genera ESPACIO_LISTO,
--   no registra entrega fisica ni cambia cupos, documentos o estado de reserva.
-- * No hay segregacion obligatoria de funciones: una cuenta multirol puede ser
--   participante de mas de una forma; no prometer que esto impide favoritismo.
-- * DDL de CREATE + ALTER puede quedar parcial si falla. No reintentar a ciegas,
--   no ejecutar Flyway repair ni eliminar tablas para ocultar una aplicacion parcial.
