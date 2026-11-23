/* BO4_WEB_UI_V1: asignacion y preparacion; una sola operacion pendiente en memoria. */
(() => {
  'use strict';
  const root = document.getElementById('asignaciones-supervisor');
  if (!root) return;
  const el = (tag, text, cls) => { const n = document.createElement(tag); if (text !== undefined) n.textContent = text; if (cls) n.className = cls; return n; };
  let api, preparacion;
  try {
    if (!root.dataset.api) throw new Error('Configuracion');
    api = new URL(root.dataset.api, location.href);
    if (api.origin !== location.origin || !/^\/supervisor\/api\/bitacora-asignaciones\/?$/.test(api.pathname) || api.search || api.hash) throw new Error('Configuracion');
    api.pathname = api.pathname.replace(/\/$/, '');
    preparacion = new URL(api); preparacion.pathname = api.pathname.replace(/bitacora-asignaciones$/, 'bitacora-preparacion');
  } catch (_) { root.append(el('p', 'No se pudo verificar la ruta de asignaciones. No se enviaron operaciones.', 'text-danger')); return; }
  let pendiente = null, ocupada = false, dudaAnterior = false, campos = [], contextoActual = null;
  root.append(el('h2', 'Asignar y preparar espacios', 'h4'));
  root.append(el('p', 'Tomar una reserva registra responsabilidad. Marcar espacio listo registra preparación y avisos internos; no acredita entrega física ni inicio del evento. El correo permanece DESHABILITADO.', 'text-muted'));
  const form = el('form', undefined, 'd-flex gap-2 align-items-end flex-wrap mb-3');
  const label = el('label', 'Mes del evento', 'form-label mb-0');
  const mes = el('input', undefined, 'form-control'); mes.type = 'month'; mes.required = true;
  const parts = new Intl.DateTimeFormat('en', { timeZone: 'America/Mexico_City', year: 'numeric', month: '2-digit' }).formatToParts(new Date());
  mes.value = parts.find(p => p.type === 'year').value + '-' + parts.find(p => p.type === 'month').value;
  label.append(mes);
  const consultarBtn = el('button', 'Consultar asignaciones', 'btn btn-primary'); consultarBtn.type = 'submit'; form.append(label, consultarBtn);
  const estado = el('p', 'Selecciona el mes y consulta.'); estado.setAttribute('role', 'status'); estado.setAttribute('aria-live', 'polite');
  const aviso = el('div', undefined, 'alert alert-warning'); aviso.hidden = true;
  const identidad = el('p');
  const retry = el('button', 'Reintentar la misma operación', 'btn btn-warning'); retry.type = 'button';
  aviso.append(el('p', 'Hay una operación pendiente de confirmar. No cambies de cuenta, no recargues ni cierres esta pestaña. Se conserva exactamente la misma clave y contenido para reintentar.'), identidad, retry);
  const filas = el('div'), detalle = el('div'); detalle.setAttribute('aria-live', 'polite');
  root.append(form, estado, aviso, filas, detalle);
  function controles() {
    const bloqueada = ocupada || pendiente !== null;
    root.querySelectorAll('button,input,textarea,select').forEach(n => { n.disabled = bloqueada || n.dataset.soloLectura === 'si'; });
    aviso.hidden = pendiente === null; retry.disabled = ocupada || pendiente === null;
    identidad.textContent = pendiente ? `${pendiente.tipo === 'tomar' ? 'Asignación' : 'Espacio listo'} · reserva ${pendiente.folio} · operación ${pendiente.clave}` : '';
  }
  function puedeSalirDelDetalle() {
    if (ocupada || pendiente) return false;
    if (campos.some(c => c.value !== '') && !window.confirm('Hay un borrador sin enviar. ¿Descartarlo para cambiar de consulta?')) return false;
    return true;
  }
  function limpiarDetalle() { detalle.replaceChildren(); campos = []; contextoActual = null; }
  async function pedir(url, options = {}) {
    const abort = new AbortController(), timer = setTimeout(() => abort.abort(), 30000);
    try {
      const r = await fetch(url, { credentials: 'same-origin', cache: 'no-store', redirect: 'error', ...options, signal: abort.signal });
      if (!(r.headers.get('Content-Type') || '').toLowerCase().includes('application/json')) throw new Error('Respuesta no verificable');
      return { r, body: await r.json() };
    } finally { clearTimeout(timer); }
  }
  function texto(value) { return typeof value === 'string' ? value : ''; }
  function bloqueTexto(parent, titulo, value) {
    parent.append(el('h4', titulo, 'h6 mt-3'));
    const p = el('p', texto(value) || 'Sin dato registrado.', 'mb-2'); p.style.whiteSpace = 'pre-wrap'; p.style.overflowWrap = 'anywhere'; parent.append(p);
  }
  function claveSegura() {
    if (!window.crypto || !window.crypto.randomUUID) { estado.textContent = 'El navegador no permite generar una clave segura. No se envió la operación.'; return null; }
    return window.crypto.randomUUID();
  }
  async function consultar(prefijo = '') {
    if (!puedeSalirDelDetalle() || !form.reportValidity()) return;
    ocupada = true; limpiarDetalle(); filas.replaceChildren(); controles(); estado.textContent = 'Consultando asignaciones…';
    try {
      const url = new URL(api); url.searchParams.set('mes', mes.value);
      const { r, body } = await pedir(url, { headers: { Accept: 'application/json' } });
      if (!r.ok) { estado.textContent = prefijo + (typeof body?.mensaje === 'string' ? body.mensaje : 'No se pudo consultar.'); return; }
      if (!body || !Array.isArray(body.reservas) || body.reservas.length > 200) throw new Error('Respuesta inválida');
      estado.textContent = prefijo + `${body.reservas.length} reserva(s) disponible(s) para tomar o asignada(s) a tu cuenta en ${texto(body.mes)}.`;
      if (!body.reservas.length) { filas.append(el('p', 'No hay reservas en esta lista para el mes seleccionado.', 'text-muted')); return; }
      const wrap = el('div', undefined, 'table-responsive'), table = el('table', undefined, 'table table-striped align-middle');
      table.append(el('caption', 'La asignación y la preparación no modifican aprobación, documentos, cupos ni horas reales.'));
      const head = el('thead'), hr = el('tr');
      ['Folio', 'Fecha y horario', 'Espacio', 'Responsabilidad', 'Acción'].forEach(t => { const h = el('th', t); h.scope = 'col'; hr.append(h); }); head.append(hr); table.append(head);
      const tbody = el('tbody');
      for (const row of body.reservas) {
        if (!row || !Number.isSafeInteger(row.id) || row.id <= 0 || typeof row.folio !== 'string') throw new Error('ID no válido');
        const tr = el('tr'), fecha = el('td'), estadoCelda = el('td'), accion = el('td');
        fecha.append(el('div', texto(row.fecha)), el('div', `${texto(row.inicio)}–${texto(row.fin)}`));
        if (row.asignacion === 'ASIGNADA_A_MI') {
          estadoCelda.append(el('span', 'Asignada a mi cuenta', 'badge text-bg-success'));
          const preparar = el('button', 'Preparar espacio', 'btn btn-outline-primary btn-sm'); preparar.type = 'button';
          preparar.addEventListener('click', () => abrirContexto(row.id)); accion.append(preparar);
        } else if (row.asignacion === 'DISPONIBLE_PARA_TOMAR') {
          estadoCelda.textContent = 'Sin supervisor responsable';
          const tomar = el('button', 'Tomar para preparación', 'btn btn-outline-primary btn-sm'); tomar.type = 'button';
          tomar.addEventListener('click', () => {
            if (!puedeSalirDelDetalle()) return;
            if (!window.confirm(`Tomar la reserva ${row.folio} para preparación. Quedará asignada a tu cuenta. Esto no aprueba, prepara ni entrega físicamente el espacio. ¿Continuar?`)) return;
            const clave = claveSegura(); if (!clave) return;
            limpiarDetalle(); dudaAnterior = false;
            pendiente = Object.freeze({ tipo: 'tomar', id: row.id, folio: row.folio, clave, json: JSON.stringify({ clave }) });
            controles(); enviar();
          }); accion.append(tomar);
        } else { throw new Error('Estado no reconocido'); }
        tr.append(el('td', row.folio), fecha, el('td', texto(row.espacio)), estadoCelda, accion); tbody.append(tr);
      }
      table.append(tbody); wrap.append(table); filas.append(wrap);
    } catch (_) { filas.replaceChildren(); estado.textContent = prefijo + 'No se obtuvo una consulta verificable. Revisa la sesión o conexión; esta consulta no envió operaciones.'; }
    finally { ocupada = false; controles(); }
  }
  async function abrirContexto(id) {
    if (!puedeSalirDelDetalle()) return;
    ocupada = true; limpiarDetalle(); controles(); estado.textContent = 'Consultando preparación privada…';
    try {
      const url = new URL(preparacion); url.pathname += `/${id}`;
      const { r, body } = await pedir(url, { headers: { Accept: 'application/json' } });
      if (!r.ok) { estado.textContent = typeof body?.mensaje === 'string' ? body.mensaje : 'No se pudo consultar la preparación.'; return; }
      if (!body || body.solicitudId !== id || typeof body.folio !== 'string' || !Number.isSafeInteger(body.versionEsperada) || body.versionEsperada < 0 ||
          typeof body.editable !== 'boolean' || body.editable !== (body.estado === 'APROBADA') || !Array.isArray(body.recursos) || body.recursos.length > 200) throw new Error('Contexto no verificable');
      if (body.ultima !== null && (!body.ultima || body.ultima.revision !== body.versionEsperada || !Number.isSafeInteger(body.ultima.eventoId) || body.ultima.eventoId <= 0)) throw new Error('Última revisión no verificable');
      if (body.ultima === null && body.versionEsperada !== 0) throw new Error('Revisión no verificable');
      contextoActual = Object.freeze({ id, folio: body.folio, revision: body.versionEsperada });
      renderContexto(body); estado.textContent = `Preparación de ${body.folio}; revisión operativa ${body.versionEsperada}. Consulta de solo lectura: aún no se ha marcado espacio listo.`;
    } catch (_) { limpiarDetalle(); estado.textContent = 'No se obtuvo un contexto privado verificable. No se envió preparación.'; }
    finally { ocupada = false; controles(); }
  }
  function renderContexto(body) {
    const box = el('section', undefined, 'border rounded p-3 mt-3');
    box.append(el('h3', `Preparación · ${body.folio}`, 'h5'));
    box.append(el('p', `${texto(body.fecha)} · ${texto(body.inicio)}–${texto(body.fin)} · ${texto(body.espacio)} · ${texto(body.estado)}`));
    bloqueTexto(box, 'Evento solicitado', body.motivoEvento);
    bloqueTexto(box, 'Recursos externos solicitados (texto capturado)', body.recursosExternosSolicitados);
    box.append(el('h4', 'Inventario escolar: instantánea al confirmar la reserva', 'h6 mt-3'));
    if (!body.recursos.length) box.append(el('p', 'No hay detalle de inventario registrado para esta reserva. Esto no demuestra disponibilidad actual.'));
    else {
      const wrap = el('div', undefined, 'table-responsive'), table = el('table', undefined, 'table table-sm');
      const head = el('thead'), tr = el('tr');
      ['ID de inventario', 'Artículo', 'Estado al confirmar', 'Cantidad solicitada'].forEach(t => { const th = el('th', t); th.scope = 'col'; tr.append(th); }); head.append(tr); table.append(head);
      const tbody = el('tbody');
      for (const r of body.recursos) {
        if (!r || !Number.isSafeInteger(r.inventarioId) || r.inventarioId <= 0 || !Number.isSafeInteger(r.cantidad) || r.cantidad <= 0) throw new Error('Recurso inválido');
        const line = el('tr'); line.append(el('td', String(r.inventarioId)), el('td', texto(r.nombre)), el('td', texto(r.estado)), el('td', String(r.cantidad))); tbody.append(line);
      }
      table.append(tbody); wrap.append(table); box.append(wrap);
    }
    if (body.ultima) {
      const last = el('details', undefined, 'mb-3'); last.append(el('summary', `Última preparación registrada: revisión ${body.ultima.revision} (no es el historial completo)`));
      last.append(el('p', `Evento ${body.ultima.eventoId} · ${texto(body.ultima.fechaUtc)}`));
      bloqueTexto(last, 'Condiciones registradas', body.ultima.condicionesEspacio);
      bloqueTexto(last, 'Materiales registrados', body.ultima.materialesPreparados);
      bloqueTexto(last, 'Recursos externos registrados', body.ultima.recursosExternosPreparados);
      bloqueTexto(last, 'Observaciones registradas', body.ultima.observaciones); box.append(last);
    }
    if (body.editable) {
      const f = el('form');
      f.append(el('p', 'Describe lo que realmente verificaste. No hay respuestas correctas prellenadas. Guardar crea una nueva revisión operativa; no entrega el espacio ni inicia el evento.'));
      const defs = [['condicionesEspacio','Condiciones del espacio',true],['materialesPreparados','Materiales preparados',true],['recursosExternosPreparados','Recursos externos preparados',true],['observaciones','Observaciones',false]];
      for (const [name,title,required] of defs) {
        const l = el('label', title + (required ? ' (obligatorio)' : ' (opcional)'), 'form-label d-block');
        const t = el('textarea', undefined, 'form-control mb-3'); t.name = name; t.rows = 3; t.maxLength = 4000; t.required = required;
        t.placeholder = required ? 'Describe la verificación; indica no aplica únicamente si corresponde.' : 'Información adicional, sin documentos ni contraseñas.';
        l.append(t); f.append(l); campos.push(t);
      }
      const checkLabel = el('label', undefined, 'd-block mb-3'), check = el('input'); check.type = 'checkbox'; check.required = true;
      checkLabel.append(check, document.createTextNode(' Verifiqué la preparación y entiendo que esto no acredita entrega física.')); f.append(checkLabel);
      const submit = el('button', 'Marcar espacio listo', 'btn btn-success'); submit.type = 'submit'; f.append(submit);
      f.addEventListener('submit', e => {
        e.preventDefault(); if (ocupada || pendiente || !contextoActual || !f.reportValidity()) return;
        if (campos.slice(0,3).some(t => !t.value.trim())) { estado.textContent = 'Describe los tres apartados obligatorios; no pueden quedar en blanco.'; return; }
        if (!window.confirm(`Registrar ESPACIO_LISTO para ${body.folio}, sobre la revisión ${body.versionEsperada}. Se registrarán avisos internos para solicitante y capturista, sin duplicar una misma persona; el correo NO se enviará. No es entrega física. ¿Continuar?`)) return;
        const clave = claveSegura(); if (!clave) return;
        const comando = { clave, versionEsperada: contextoActual.revision, condicionesEspacio: campos[0].value, materialesPreparados: campos[1].value, recursosExternosPreparados: campos[2].value, observaciones: campos[3].value === '' ? null : campos[3].value };
        dudaAnterior = false;
        pendiente = Object.freeze({ tipo: 'listo', id: contextoActual.id, folio: contextoActual.folio, clave, revision: comando.versionEsperada, json: JSON.stringify(comando) });
        controles(); enviar();
      }); box.append(f);
    } else box.append(el('p', 'La reserva ya no está aprobada. Este detalle no habilita registrar otra preparación.', 'text-warning'));
    const cerrar = el('button', 'Cerrar detalle', 'btn btn-outline-secondary mt-3'); cerrar.type = 'button';
    cerrar.addEventListener('click', () => { if (puedeSalirDelDetalle()) { limpiarDetalle(); controles(); } }); box.append(cerrar);
    detalle.replaceChildren(box);
  }
  async function enviar() {
    if (ocupada || !pendiente) return;
    const operacion = pendiente; ocupada = true; controles(); estado.textContent = operacion.tipo === 'tomar' ? 'Confirmando asignación…' : 'Confirmando espacio listo…';
    let enviado = false, refrescarAsignaciones = false, mensajeAsignacion = '';
    try {
      // Un token fresco en CADA intento, pero nunca UUID/JSON nuevos mientras haya pendiente.
      const base = operacion.tipo === 'tomar' ? api : preparacion;
      const csrfUrl = new URL(base); csrfUrl.pathname += '/csrf';
      const csrf = await pedir(csrfUrl, { headers: { Accept: 'application/json' } });
      if (!csrf.r.ok || !csrf.body || !/^X-(?:CSRF|XSRF)-TOKEN$/i.test(csrf.body.headerName || '') || typeof csrf.body.token !== 'string' || !csrf.body.token) throw new Error('CSRF no verificable');
      const headers = { Accept: 'application/json', 'Content-Type': 'application/json' }; headers[csrf.body.headerName] = csrf.body.token;
      const url = new URL(base); url.pathname += `/${operacion.id}/${operacion.tipo === 'tomar' ? 'tomar' : 'listo'}`;
      enviado = true;
      const { r, body } = await pedir(url, { method: 'POST', headers, body: operacion.json });
      if (r.ok) {
        if (!body || body.solicitudId !== operacion.id || typeof body.repetida !== 'boolean') throw new Error('Resultado no verificable');
        if (operacion.tipo === 'tomar') {
          if (!Number.isSafeInteger(body.supervisorId) || body.supervisorId <= 0) throw new Error('Asignación no verificable');
          pendiente = null; dudaAnterior = false; refrescarAsignaciones = true;
          mensajeAsignacion = `Asignación confirmada para ${operacion.folio}${body.repetida ? ' mediante reintento' : ''}. `;
        } else {
          if (!Number.isSafeInteger(body.eventoId) || body.eventoId <= 0 || !Number.isSafeInteger(body.revision) || body.revision !== operacion.revision + 1 ||
              typeof body.fechaUtc !== 'string' || !/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z$/.test(body.fechaUtc)) throw new Error('Preparación no verificable');
          pendiente = null; dudaAnterior = false; limpiarDetalle();
          estado.textContent = `Preparación confirmada de ${operacion.folio}: evento ${body.eventoId}, revisión ${body.revision}, ${body.fechaUtc}${body.repetida ? ' (misma operación reintentada)' : ''}. Aviso interno registrado; correo DESHABILITADO, no enviado. No acredita lectura, entrega física ni inicio del evento.`;
          const consultarOtra = el('button', 'Consultar preparación registrada', 'btn btn-outline-primary'); consultarOtra.type = 'button'; consultarOtra.addEventListener('click', () => abrirContexto(operacion.id)); detalle.append(consultarOtra);
        }
      } else if ([400,403,404,409].includes(r.status) && body && body.resultadoIncierto === false && !dudaAnterior) {
        pendiente = null;
        estado.textContent = typeof body.mensaje === 'string' ? body.mensaje : 'Operación no aplicada. Consulta el detalle antes de intentar una operación nueva.';
      } else { throw new Error('Resultado incierto o intento anterior sin resolver'); }
    } catch (_) {
      // Un rechazo posterior NO demuestra que un POST anterior dudoso no se haya confirmado.
      if (enviado) dudaAnterior = true;
      estado.textContent = 'No se pudo confirmar el resultado. Conservamos la clave y contenido sin cambios. Reintenta la misma operación; si venció la sesión, inicia sesión en otra pestaña con la misma cuenta. Un rechazo posterior no descarta un resultado anterior incierto. No generes otra clave ni recargues para desbloquear.';
    } finally { ocupada = false; controles(); }
    if (refrescarAsignaciones) await consultar(mensajeAsignacion);
  }
  form.addEventListener('submit', e => { e.preventDefault(); consultar(); });
  retry.addEventListener('click', enviar);
  window.addEventListener('beforeunload', e => { if (pendiente) { e.preventDefault(); e.returnValue = ''; } });
})();
