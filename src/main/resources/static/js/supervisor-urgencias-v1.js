/* SupervisorUrgencias UI 2.1. Nombre historico v1 conservado. Sin HTML dinamico ni almacenamiento persistente. */
(() => {
  'use strict';
  const root = document.getElementById('urgencias-supervisor'); if (!root) return;
  const $ = id => document.getElementById(id);
  const list = $('urg-lista');
  const message = text => { $('urg-mensaje').textContent = text; };
  const node = (tag, text, cls) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = String(text); if (cls) e.className = cls; return e; };
  const button = (text, cls = '') => { const e = node('button', text, 'urg-button ' + cls); e.type = 'button'; return e; };
  const shown = v => v === null || v === undefined || v === '' ? '—' : String(v);
  let api;
  try {
    const url = new URL(root.dataset.api, location.href);
    if (!root.dataset.api || url.origin !== location.origin || url.search || url.hash || url.username || url.password) throw new Error();
    api = url.pathname.replace(/\/$/, '');
  } catch (_) { message('No se encontró una ruta local válida del módulo. No se enviaron solicitudes.'); return; }
  let pending = null, busy = false, epoch = 0;
  const pdfs = new Map();
  const MAX_PDF = 3000000;
  const actionNames = { APROBAR: 'Aprobar reserva', RECHAZAR: 'Rechazar reserva', VALIDAR_DOCUMENTOS: 'Validar documentos', OBSERVAR_DOCUMENTOS: 'Observar documentos' };
  const effects = {
    APROBAR: 'Aprobará la reserva si supera las validaciones del servidor; no creará otro consumo de cupo.',
    RECHAZAR: 'Rechazará la reserva, marcará sus documentos NO_APLICA y reintegrará una sola vez el consumo histórico.',
    VALIDAR_DOCUMENTOS: 'Registrará documentos VALIDADA sin cancelar la reserva ni reintegrar cupo.',
    OBSERVAR_DOCUMENTOS: 'Registrará documentos OBSERVADA y el motivo. No cancela ni reintegra cupo y no envía una notificación.'
  };
  function freeze() {
    $('urg-cargar').disabled = !!pending || busy; $('urg-mes').disabled = !!pending || busy;
    list.querySelectorAll('[data-editable]').forEach(e => { e.disabled = !!pending || busy; });
    $('urg-intento').hidden = !pending; $('urg-reintentar').disabled = busy;
    if (pending) $('urg-intento-texto').textContent = 'Operación conservada: ' + pending.key + ' · ' + pending.folio + ' · reserva ' + pending.id + '. UUID y contenido no cambiarán al reintentar. No cierres ni recargues esta pestaña. Si venció la sesión, autentícate en otra pestaña y reintenta aquí.';
  }
  async function json(response) { try { return await response.json(); } catch (_) { return null; } }
  function problem(r, data, fallback) {
    if (data && typeof data.mensaje === 'string') return data.mensaje + ' (HTTP ' + r.status + ')';
    if (r.status === 401 || r.status === 403) return 'Sesión vencida o acceso no autorizado (HTTP ' + r.status + ').';
    if (r.status === 404) return 'Reserva o recurso no disponible (HTTP 404).';
    return fallback + ' (HTTP ' + r.status + ').';
  }
  function releasePdf(url, expired = false) {
    const info = pdfs.get(url); if (!info) return;
    clearTimeout(info.timer); URL.revokeObjectURL(url); pdfs.delete(url);
    if (expired) info.expired();
  }
  function releaseAll() { for (const url of [...pdfs.keys()]) releasePdf(url); }
  function clearList() { epoch++; releaseAll(); list.replaceChildren(); }
  function field(dl, title, value, preserve = false) {
    dl.append(node('dt', title)); dl.append(node('dd', shown(value), preserve ? 'urg-prewrap' : ''));
  }
  function modeText(mode) { return mode === 'URGENTE_REQUISICION' ? 'Urgencia con requisición' : mode === 'URGENTE_INVENTARIO' ? 'Urgencia de inventario' : shown(mode); }
  function status(text) { return node('span', shown(text), 'urg-state'); }
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Mexico_City', year: 'numeric', month: '2-digit' }).formatToParts(new Date());
  $('urg-mes').value = parts.find(x => x.type === 'year').value + '-' + parts.find(x => x.type === 'month').value;
  async function load() {
    if (pending || busy) return false;
    if (!$('urg-mes').validity.valid || !/^\d{4}-\d{2}$/.test($('urg-mes').value)) { message('Indica un mes válido.'); return false; }
    busy = true; freeze(); clearList(); $('urg-conteos').textContent = ''; message('Consultando reservas urgentes…');
    try {
      const r = await fetch(api + '?mes=' + encodeURIComponent($('urg-mes').value), { credentials: 'same-origin', cache: 'no-store', redirect: 'error' });
      const data = await json(r);
      if (!r.ok || !data || !Array.isArray(data.solicitudes)) throw new Error(problem(r, data, 'No se pudo consultar el mes'));
      $('urg-conteos').textContent = 'Total: ' + shown(data.total) + ' · Pendientes: ' + shown(data.pendientes) + ' · Aprobadas: ' + shown(data.aprobadas) + ' · Rechazadas: ' + shown(data.rechazadas);
      if (!data.solicitudes.length) list.append(node('p', 'No hay reservas urgentes para el mes consultado.', 'urg-empty'));
      else {
        const wrap = node('div', undefined, 'urg-table-wrap');
        const table = node('table', undefined, 'urg-table');
        const caption = node('caption', 'Reservas urgentes del mes. Abre Detalle para documentos, recursos, historial y decisiones.');
        const head = node('thead'), hr = node('tr');
        for (const title of ['Folio', 'Evento', 'Espacio', 'Modalidad', 'Reserva', 'Documentos', 'Consulta']) { const th = node('th', title); th.scope = 'col'; hr.append(th); }
        head.append(hr); const body = node('tbody'); table.append(caption, head, body); wrap.append(table); list.append(wrap);
        data.solicitudes.forEach((row, i) => render(row, body, i));
      }
      message('Consulta completa del mes. NORMAL no se incluye. Consultar no registra una decisión.'); return true;
    } catch (e) { message(e.message || 'Consulta no disponible.'); return false; }
    finally { busy = false; freeze(); }
  }
  function render(row, body, index) {
    if (!row || !Number.isSafeInteger(row.id) || row.id <= 0) throw new Error('El servidor devolvió una identidad de reserva no representable de forma segura. No se habilitarán decisiones.');
    const id = encodeURIComponent(String(row.id));
    const summary = node('tr');
    const folio = node('th', shown(row.folio)); folio.scope = 'row'; summary.append(folio);
    summary.append(node('td', shown(row.fecha) + '\n' + shown(row.inicio) + '–' + shown(row.fin), 'urg-prewrap'), node('td', shown(row.espacio)), node('td', modeText(row.modalidad)));
    for (const value of [row.estado, row.documentos]) { const td = node('td'); td.append(status(value)); summary.append(td); }
    const tdToggle = node('td'), toggle = button('Detalle'); tdToggle.append(toggle); summary.append(tdToggle);
    const detailRow = node('tr', undefined, 'urg-detail-row'); detailRow.hidden = true;
    const detailCell = node('td'); detailCell.colSpan = 7;
    const detail = node('article', undefined, 'urg-detail'); detail.id = 'urg-detalle-' + index;
    toggle.setAttribute('aria-controls', detail.id); toggle.setAttribute('aria-expanded', 'false');
    const title = node('h3', 'Detalle · ' + shown(row.folio)); title.id = detail.id + '-titulo'; detail.setAttribute('aria-labelledby', title.id); detail.append(title);
    const dl = node('dl', undefined, 'urg-fields');
    field(dl, 'Solicitante', row.solicitante); field(dl, 'Departamento original', row.departamentoOriginal);
    field(dl, 'Fecha y horario', shown(row.fecha) + ' · ' + shown(row.inicio) + '–' + shown(row.fin));
    field(dl, 'Espacio', row.espacio); field(dl, 'Motivo del evento', row.motivo, true); field(dl, 'Revisión', row.revision);
    detail.append(dl);
    const resources = node('section', undefined, 'urg-panel'); resources.append(node('h4', 'Recursos de la institución (al confirmar)'));
    if (Array.isArray(row.recursos) && row.recursos.length) {
      const ul = node('ul');
      for (const item of row.recursos) ul.append(node('li', shown(item.nombre) + ' · cantidad: ' + shown(item.cantidad) + ' · estado histórico: ' + shown(item.estadoHistorico)));
      resources.append(ul);
    } else resources.append(node('p', 'No hay recursos de la institución registrados en este detalle.'));
    detail.append(resources);
    const external = node('section', undefined, 'urg-panel'); external.append(node('h4', 'Recursos / proveedor externo declarados'));
    if (!Object.prototype.hasOwnProperty.call(row, 'recursosExternos')) external.append(node('p', 'El servidor no incluyó el campo de recursos externos. Revisa que el parche del DTO esté instalado; no se interpreta como ausencia de recursos.', 'urg-warning'));
    else external.append(node('p', typeof row.recursosExternos === 'string' && row.recursosExternos.trim() ? row.recursosExternos : 'No se declaró texto de recursos externos.', 'urg-prewrap'));
    external.append(node('p', 'Se muestra el texto capturado, sin convertirlo en cantidades o artículos del inventario de la institución.', 'urg-help')); detail.append(external);
    const docSection = node('section', undefined, 'urg-panel'); docSection.append(node('h4', 'Documentos registrados'));
    docSection.append(node('p', 'Una referencia registrada no demuestra que el PDF esté disponible. Se comprobará solo cuando pulses Consultar. Los enlaces temporales no se guardan.', 'urg-help'));
    const docs = [];
    let serial = 0;
    for (const [type, name, registered] of [['oficio', 'Oficio', row.oficioRegistrado], ['requisicion', 'Requisición', row.requisicionRegistrada]]) {
      const box = node('div', undefined, 'urg-document'); box.append(node('strong', name + ' · referencia registrada: ' + (registered === true ? 'sí' : registered === false ? 'no' : 'sin dato')));
      const getPdf = button('Consultar ' + name.toLowerCase());
      const out = node('div', 'Disponibilidad todavía no consultada.', 'urg-doc-result'); out.setAttribute('role', 'status'); out.setAttribute('aria-live', 'polite');
      let controller = null, ownUrl = null;
      const reset = () => { if (controller) controller.abort(); if (ownUrl) releasePdf(ownUrl); ownUrl = null; out.replaceChildren(node('p', 'Disponibilidad todavía no consultada.')); };
      docs.push(reset);
      getPdf.addEventListener('click', async () => {
        if (controller) return;
        const ticket = serial, page = epoch; controller = new AbortController(); getPdf.disabled = true;
        if (ownUrl) releasePdf(ownUrl); ownUrl = null; out.replaceChildren(node('p', 'Consultando documento…'));
        try {
          const r = await fetch(api + '/' + id + '/documentos/' + type, { credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: controller.signal });
          if (!r.ok) { const data = await json(r); throw new Error(problem(r, data, 'Documento no disponible')); }
          const blob = await readPdf(r);
          if (!detailRow.isConnected || detailRow.hidden || ticket !== serial || page !== epoch) return;
          ownUrl = URL.createObjectURL(blob); const url = ownUrl;
          const open = node('a', 'Abrir PDF en otra pestaña', 'urg-button'); open.href = url; open.target = '_blank'; open.rel = 'noopener noreferrer'; open.referrerPolicy = 'no-referrer';
          const download = node('a', 'Descargar PDF', 'urg-button'); download.href = url; download.download = type + '-' + row.id + '.pdf'; download.referrerPolicy = 'no-referrer';
          out.replaceChildren(node('p', 'PDF recibido. Elige abrir o descargar; los enlaces caducan en cinco minutos o al cerrar el detalle.'), open, document.createTextNode(' '), download);
          pdfs.set(url, { timer: setTimeout(() => releasePdf(url, true), 300000), expired: () => { if (ownUrl === url) { ownUrl = null; out.replaceChildren(node('p', 'Enlaces caducados. Vuelve a consultar para abrir o descargar.')); } } });
        } catch (e) {
          if (e.name !== 'AbortError' && detailRow.isConnected && !detailRow.hidden && ticket === serial && page === epoch) out.replaceChildren(node('p', e.message || 'No se pudo consultar el documento. Comprueba la sesión.'));
        } finally { controller = null; getPdf.disabled = false; }
      });
      box.append(getPdf, out); docSection.append(box);
    }
    detail.append(docSection);
    const historyBox = node('section', undefined, 'urg-panel'); historyBox.append(node('h4', 'Historial de decisiones y documentos'));
    const history = button('Consultar historial'); const output = node('div'); output.setAttribute('role', 'status'); output.setAttribute('aria-live', 'polite');
    history.addEventListener('click', async () => {
      history.disabled = true; const page = epoch; output.replaceChildren(node('p', 'Consultando historial…'));
      try {
        const r = await fetch(api + '/' + id + '/historial', { credentials: 'same-origin', cache: 'no-store', redirect: 'error' }); const data = await json(r);
        if (!r.ok || !Array.isArray(data)) throw new Error(problem(r, data, 'Historial no disponible'));
        if (!detailRow.isConnected || page !== epoch) return;
        output.replaceChildren();
        if (!data.length) output.append(node('p', 'Sin revisiones registradas. No hay todavía un motivo de decisión en este historial.'));
        for (const event of data) {
          const entry = node('article', undefined, 'urg-event'); entry.append(node('h5', 'Revisión ' + shown(event.revision) + ' · ' + (actionNames[event.accion] || shown(event.accion))));
          entry.append(node('p', shown(event.fecha) + ' · ' + shown(event.actor)), node('p', 'Reserva: ' + shown(event.estadoAntes) + ' → ' + shown(event.estadoDespues) + ' · Documentos: ' + shown(event.documentosAntes) + ' → ' + shown(event.documentosDespues)));
          entry.append(node('p', 'Motivo: ' + shown(event.motivo), 'urg-prewrap'), node('p', 'Reintegro registrado: ' + (event.reintegrado === true ? 'sí' : 'no')));
          const technical = node('details'); technical.append(node('summary', 'Identidad de la operación (UUID)'), node('p', shown(event.clave), 'urg-prewrap')); entry.append(technical); output.append(entry);
        }
      } catch (e) { if (detailRow.isConnected && page === epoch) output.replaceChildren(node('p', e.message || 'Historial no disponible.')); }
      finally { history.disabled = false; }
    }); historyBox.append(history, output); detail.append(historyBox);
    const actions = row.estado === 'PENDIENTE' && row.modalidad === 'URGENTE_REQUISICION' ? ['APROBAR', 'RECHAZAR'] : row.estado === 'APROBADA' ? ['VALIDAR_DOCUMENTOS', 'OBSERVAR_DOCUMENTOS'] : [];
    const decision = node('section', undefined, 'urg-panel'); decision.append(node('h4', 'Revisión de esta reserva'));
    decision.append(node('p', row.modalidad === 'URGENTE_INVENTARIO' ? 'La urgencia de inventario se confirma aprobada. Aquí se revisan sus documentos; observarlos no cancela ni reintegra cupo.' : 'La reserva y sus documentos tienen estados distintos. Cada operación deja motivo en el historial.'));
    decision.append(node('p', 'Observar documentos registra la observación; esta entrega no envía avisos ni implementa todavía la sustitución o el reenvío del archivo por el solicitante.', 'urg-help'));
    if (actions.length) {
      const reason = node('textarea'); reason.id = 'urg-motivo-' + index; reason.maxLength = 255; reason.rows = 3; reason.dataset.editable = 'true';
      const label = node('label', 'Motivo de esta decisión u observación (obligatorio, hasta 255 caracteres)'); label.htmlFor = reason.id;
      const reviewed = node('input'); reviewed.id = 'urg-revisado-' + index; reviewed.type = 'checkbox'; reviewed.dataset.editable = 'true';
      const reviewLabel = node('label', ' Revisé manualmente los documentos registrados (o dejé constancia de su ausencia para rechazar).', 'urg-check'); reviewLabel.htmlFor = reviewed.id; reviewLabel.prepend(reviewed);
      decision.append(label, reason, reviewLabel);
      for (const action of actions) {
        const block = node('div', undefined, 'urg-action'); const b = button(actionNames[action], action === 'RECHAZAR' ? 'urg-danger' : ''); b.dataset.editable = 'true'; b.dataset.decision = 'true';
        b.addEventListener('click', () => begin(row, action, reason.value, reviewed.checked)); block.append(b, node('p', effects[action], 'urg-help')); decision.append(block);
      }
    } else decision.append(node('p', 'No hay una acción de revisión habilitada para el estado actual de esta reserva.'));
    detail.append(decision); detailCell.append(detail); detailRow.append(detailCell); body.append(summary, detailRow);
    toggle.addEventListener('click', () => {
      detailRow.hidden = !detailRow.hidden; toggle.textContent = detailRow.hidden ? 'Detalle' : 'Cerrar detalle'; toggle.setAttribute('aria-expanded', String(!detailRow.hidden));
      if (detailRow.hidden) { serial++; docs.forEach(reset => reset()); }
    });
  }
  async function readPdf(r) {
    if ((r.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase() !== 'application/pdf') throw new Error('El servidor no devolvió un PDF. No se abrió ninguna pestaña.');
    const size = r.headers.get('Content-Length');
    if (size && (!/^\d+$/.test(size) || Number(size) <= 0 || Number(size) > MAX_PDF)) throw new Error('Documento vacío o mayor de 3 MB.');
    if (!r.body || !r.body.getReader) throw new Error('El navegador no permite comprobar este documento de forma acotada.');
    const reader = r.body.getReader(), chunks = []; let total = 0;
    try {
      while (true) { const result = await reader.read(); if (result.done) break; total += result.value.byteLength; if (total > MAX_PDF) { await reader.cancel(); throw new Error('El PDF supera 3 MB; consulta detenida.'); } chunks.push(result.value); }
    } finally { reader.releaseLock(); }
    if (total < 5) throw new Error('El documento está vacío o no es un PDF legible.');
    const blob = new Blob(chunks, { type: 'application/pdf' }); const magic = new Uint8Array(await blob.slice(0, 5).arrayBuffer());
    if (String.fromCharCode(...magic) !== '%PDF-') throw new Error('La respuesta no tiene cabecera PDF válida.');
    return blob;
  }
  function uuid() {
    if (!globalThis.crypto?.getRandomValues) throw new Error('No hay generador criptográfico disponible; operación bloqueada.');
    const b = new Uint8Array(16); crypto.getRandomValues(b); b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
    const h = [...b].map(v => v.toString(16).padStart(2, '0')).join(''); return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
  }
  function begin(row, action, reason, reviewed) {
    if (pending || busy) return;
    if (!Number.isSafeInteger(row.id) || row.id <= 0 || typeof row.version !== 'string' || !row.version) { message('Falta una identidad o versión válida. Vuelve a consultar antes de decidir.'); return; }
    if (!reason.trim() || reason.length > 255) { message('Indica un motivo de 1 a 255 caracteres.'); return; }
    if (action !== 'OBSERVAR_DOCUMENTOS' && !reviewed) { message('Confirma la revisión manual de los documentos.'); return; }
    if (!confirm(actionNames[action] + ': ' + row.folio + '\nMotivo: ' + reason + '\n' + effects[action] + '\n¿Confirmar?')) return;
    try {
      const key = uuid();
      pending = Object.freeze({ id: row.id, folio: shown(row.folio), key, body: JSON.stringify({ clave: key, versionEsperada: row.version, accion: action, motivo: reason, documentosRevisados: reviewed }) });
      freeze(); send();
    } catch (e) { message(e.message); }
  }
  async function send() {
    if (!pending || busy) return;
    busy = true; freeze(); let postStarted = false;
    try {
      // CSRF de la sesion ACTUAL en primer envio y reintento. Nunca usar metatags antiguos.
      const cr = await fetch(api + '/csrf', { credentials: 'same-origin', cache: 'no-store', redirect: 'error' }); const csrf = await json(cr);
      if (!cr.ok || !csrf || typeof csrf.headerName !== 'string' || !csrf.headerName || typeof csrf.token !== 'string' || !csrf.token) throw new Error('No se obtuvo CSRF autenticado');
      postStarted = true;
      const r = await fetch(api + '/' + pending.id + '/revision', { method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: pending.body });
      const data = await json(r);
      if (r.ok && data && data.solicitudId === pending.id && typeof data.repetida === 'boolean') {
        const text = data.repetida ? 'Operación ya registrada; no se duplicó.' : 'Operación registrada.';
        pending = null; busy = false; freeze(); const refreshed = await load(); message(text + (refreshed ? '' : ' No se pudo refrescar el listado; consulta antes de otra decisión.')); return;
      }
      // Solo el rechazo de negocio estructurado 400/409 permite descartar esta identidad.
      if ((r.status === 400 || r.status === 409) && data && data.resultadoIncierto === false && typeof data.mensaje === 'string') {
        pending = null; clearList(); $('urg-conteos').textContent = ''; message(data.mensaje + ' Vuelve a consultar antes de una nueva decisión.');
      } else message((data?.mensaje || 'Resultado incierto o sesión no disponible.') + ' La clave y el contenido siguen intactos. Autentícate en otra pestaña si hace falta y reintenta aquí, sin recargar.');
    } catch (_) {
      message(postStarted ? 'Resultado incierto por red o respuesta. Se conservan la operación y su UUID. Reintenta aquí sin recargar; autentícate en otra pestaña si hace falta.' : 'No se obtuvo CSRF de la sesión actual; no se envió este intento. UUID y contenido permanecen intactos. Autentícate en otra pestaña y reintenta aquí sin recargar.');
    } finally { busy = false; freeze(); }
  }
  $('urg-cargar').addEventListener('click', load); $('urg-reintentar').addEventListener('click', send);
  window.addEventListener('beforeunload', e => { if (pending) { e.preventDefault(); e.returnValue = ''; } });
  window.addEventListener('pagehide', releaseAll);
  // Sin localStorage/sessionStorage. Ignorar el aviso de salida pierde la memoria local: conciliar UUID con historial antes de crear otra operacion.
  load();
})();
