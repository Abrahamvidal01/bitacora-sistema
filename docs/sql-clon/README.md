# Supervisor: avance en desarrollo

Estado documentado: 2026-11-24.

## SQL del clon
V11 y V12 fueron aplicadas al clon y su historial fue verificado.
Estas copias se conservan sin editar, fuera de las rutas automaticas de Flyway.
NO ejecutar manualmente, repetir en el clon ni copiar a db/migration.
Los comentarios originales de borrador son historicos.
No autorizan cambios en la base original.

## Evidencia anterior
- Urgencias: 15 pruebas de integracion aprobadas y 1 omitida por falta de otra cuenta autorizada.
- Permisos: 9 casos aislados aprobados.
- Recursos externos: 7 casos unitarios aprobados.
- Consulta y asignacion: 26 casos unitarios aprobados.
- Servicio de preparacion: 53 casos unitarios aprobados.
No son resultados de una nueva ejecucion de estos commits.
Los mocks no prueban SQL, transacciones ni concurrencia reales.

## Pendientes
- Diagnosticar el HTTP 404 de urgencias en el arranque del clon.
- Urgencias requiere bitacora.supervisor.revision-habilitada=true; la bandera de confirmacion es independiente.
- Adaptar la guarda V1-V10 antes de ejecutar la suite con V11/V12.
- Revisar reintentos de urgencias despues de resultados inciertos.
- Completar pruebas HTTP, CSRF, privacidad, rollback y concurrencia de preparacion.
- Completar bandeja privada de avisos y entrega/devolucion fisica.
- SMTP permanece deshabilitado; registrar avisos no acredita envio ni lectura.
- Verificar restauracion de respaldos; tamano y hash no la demuestran.

No se ha promovido este avance a la base original.
