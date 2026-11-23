(() => {
  'use strict';
  const root = document.getElementById('bitacora-operativa-supervisor');
  if (!root) return;
  const node = (tag, text, cls) => { const n = document.createElement(tag); if (text !== undefined) n.textContent = text; if (cls) n.className = cls; return n; };
  root.append(node('h2', 'Bitácora de entrega y recepción', 'h4'));
  root.append(node('p', 'Consulta de reservas aprobadas vinculadas a tu cuenta como solicitante, capturista o supervisor asignado. Aun no registra espacio listo, entregas, avisos ni correos.', 'text-muted'));
  const form = node('form', undefined, 'd-flex gap-2 align-items-end flex-wrap mb-3');
  const label = node('label', 'Mes del evento', 'form-label mb-0');
  const input = node('input', undefined, 'form-control'); input.type = 'month'; input.required = true;
  const parts = new Intl.DateTimeFormat('en', { timeZone: 'America/Mexico_City', year: 'numeric', month: '2-digit' }).formatToParts(new Date());
  input.value = parts.find(p => p.type === 'year').value + '-' + parts.find(p => p.type === 'month').value;
  label.append(input); const button = node('button', 'Consultar bitácora', 'btn btn-primary'); button.type = 'submit'; form.append(label, button); root.append(form);
  const status = node('p', 'Selecciona el mes y consulta.'); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite');
  const results = node('div'); root.append(status, results);
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (!form.reportValidity()) return;
    button.disabled = true; input.disabled = true; results.replaceChildren(); status.textContent = 'Consultando…';
    const abort = new AbortController(); const timeout = setTimeout(() => abort.abort(), 30000);
    try {
      const url = new URL(root.dataset.api, location.href); if (url.origin !== location.origin) throw new Error('Origen no permitido');
      url.searchParams.set('mes', input.value);
      const response = await fetch(url, { credentials: 'same-origin', cache: 'no-store', redirect: 'error', headers: { Accept: 'application/json' }, signal: abort.signal });
      if (!response.ok) {
        status.textContent = response.status === 403 || response.status === 401 ? 'Acceso no autorizado o sesión vencida. Inicia sesión nuevamente.' : response.status === 409 ? 'El mes supera el límite de consulta. No se muestran resultados parciales.' : 'No se pudo consultar la bitácora. No se modificaron registros desde esta consulta.';
        return;
      }
      if (!(response.headers.get('Content-Type') || '').includes('application/json')) throw new Error('Respuesta no JSON');
      const data = await response.json(); if (!data || !Array.isArray(data.reservas) || data.reservas.length > 200) throw new Error('Respuesta no válida');
      status.textContent = `${data.reservas.length} reserva(s) aprobada(s) en ${data.mes}.`;
      if (!data.reservas.length) { results.append(node('p', 'No hay reservas aprobadas para este mes.', 'text-muted')); return; }
      const wrap = node('div', undefined, 'table-responsive'); const table = node('table', undefined, 'table table-striped align-middle');
      table.append(node('caption', 'Consulta de reservas aprobadas. Los horarios del evento no son horas reales de entrega o devolución.'));
      const head = node('thead'); const hr = node('tr');
      ['Folio y evento', 'Fecha y horario programados', 'Espacio y departamento', 'Bitácora', 'Detalle'].forEach(text => { const th = node('th', text); th.scope = 'col'; hr.append(th); }); head.append(hr); table.append(head);
      const body = node('tbody');
      for (const r of data.reservas) {
        const tr = node('tr');
        const first = node('td'); first.append(node('strong', r.folio), node('div', r.evento));
        const schedule = node('td'); schedule.append(node('div', r.fecha), node('div', `${r.inicio}–${r.fin}`));
        const place = node('td'); place.append(node('div', r.espacio), node('div', r.departamento, 'small text-muted'));
        const state = node('td', r.estadoBitacora);
        const last = node('td'); const detail = node('details'); detail.append(node('summary', 'Ver datos'));
        detail.append(node('p', `Recursos externos declarados: ${r.recursosExternos || 'Sin descripción registrada'}`, 'mt-2'));
        detail.lastChild.style.whiteSpace = 'pre-wrap';
        detail.append(node('p', `Entrada real registrada: ${r.entradaReal || 'Sin registrar'}`));
        detail.append(node('p', `Salida real registrada: ${r.salidaReal || 'Sin registrar'}`));
        last.append(detail); tr.append(first, schedule, place, state, last); body.append(tr);
      }
      table.append(body); wrap.append(table); results.append(wrap);
    } catch (_) { status.textContent = 'No se obtuvo una consulta verificable. Revisa tu sesión y conexión; esta sección no envía operaciones de escritura.'; }
    finally { clearTimeout(timeout); button.disabled = false; input.disabled = false; }
  });
})();
