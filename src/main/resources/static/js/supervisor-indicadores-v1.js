/* supervisor-indicadores-v1.js | Static proposal, not browser-tested. */
(function () {
  'use strict';
  var root = document.getElementById('indicadores-supervisor');
  if (!root || root.dataset.kpiInitialized === '1') return;
  root.dataset.kpiInitialized = '1';
  var sequence = 0, currentController = null, values = [];
  var definitions = [
    ['pendientesDictamen', 'Pendientes de dictamen', 'PENDIENTE y URGENTE_REQUISICION, por fecha del evento.', 'warning', 'bi-hourglass-split'],
    ['aprobadasMes', 'Aprobadas del mes', 'APROBADA en cualquier modalidad, por fecha del evento.', 'success', 'bi-check-circle-fill'],
    ['urgentesMes', 'Urgentes del mes', 'Reservas urgentes del mes, incluidas las pendientes, aprobadas, rechazadas y canceladas.', 'danger', 'bi-lightning-fill'],
    ['normalesMes', 'Normales del mes', 'Reservas Normales del mes, incluidas las pendientes, aprobadas, rechazadas y canceladas.', 'primary', 'bi-calendar-check'],
    ['usuariosActivosActual', 'Usuarios activos', 'Cuentas habilitadas en el sistema. Este indicador es independiente del mes seleccionado.', 'secondary', 'bi-people-fill']
  ];
  function element(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function validMonth(value) {
    return typeof value === 'string' && /^[1-9][0-9]{3}-(0[1-9]|1[0-2])$/.test(value) && Number(value.slice(0, 4)) <= 9998;
  }
  function monthInMexicoCity() {
    var parts = new Intl.DateTimeFormat('en-US', {timeZone: 'America/Mexico_City', year: 'numeric', month: '2-digit'}).formatToParts(new Date());
    var year = '', month = '';
    parts.forEach(function (part) {
      if (part.type === 'year') year = part.value;
      if (part.type === 'month') month = part.value;
    });
    var value = year + '-' + month;
    if (!validMonth(value)) throw new Error('MONTH_DEFAULT_UNAVAILABLE');
    return value;
  }
  function apiUrl() {
    var raw = root.dataset.api;
    var endpointPattern = new RegExp('^/(?:[A-Za-z0-9_-]+/)*supervisor/api/indicadores$');
    if (typeof raw !== 'string' || !endpointPattern.test(raw)) throw new Error('INVALID_ENDPOINT');
    var url = new URL(raw, window.location.origin);
    if (url.origin !== window.location.origin || url.username || url.password || url.search || url.hash || url.pathname !== raw) throw new Error('INVALID_ENDPOINT');
    return url;
  }
  function clearValues() { values.forEach(function (node) { node.textContent = '—'; }); }
  function setMessage(message, danger) {
    status.className = danger ? 'alert alert-warning py-2 mb-3' : 'text-muted small mb-3';
    status.textContent = message;
  }
  function disableControls(disabled) {
    monthInput.disabled = disabled;
    button.disabled = disabled;
    root.setAttribute('aria-busy', disabled ? 'true' : 'false');
  }
  function validateBody(data, expectedMonth) {
    if (!data || typeof data !== 'object' || Array.isArray(data) || data.mes !== expectedMonth) return false;
    var keys = ['mes'].concat(definitions.map(function (definition) { return definition[0]; }));
    if (Object.keys(data).length !== keys.length) return false;
    return keys.every(function (key) {
      if (!Object.prototype.hasOwnProperty.call(data, key)) return false;
      return key === 'mes' || (typeof data[key] === 'number' && Number.isSafeInteger(data[key]) && data[key] >= 0);
    });
  }
  function httpMessage(code) {
    if (code === 401 || code === 403) return 'No tienes una sesión de supervisor autorizada. Vuelve a iniciar sesión.';
    if (code === 400) return 'El mes no es válido. Usa un mes entre 1000-01 y 9998-12.';
    if (code === 409) return 'Indicadores no disponibles: hay datos del mes sin modalidad o estado reconocible, o conteos no publicables. No se muestran ceros sustitutos.';
    return 'No fue posible consultar los indicadores. No se muestran valores anteriores. Intenta de nuevo.';
  }
  root.appendChild(element('p', 'text-muted small', 'Conteos institucionales. Las primeras cuatro tarjetas usan el mes de la fecha del evento.'));
  var form = element('form', 'd-flex align-items-end flex-wrap gap-2 mb-2');
  var field = element('div');
  var label = element('label', 'form-label mb-1', 'Mes de la fecha del evento');
  label.htmlFor = 'indicadores-supervisor-mes';
  var monthInput = element('input', 'form-control');
  monthInput.id = 'indicadores-supervisor-mes';
  monthInput.name = 'mes'; monthInput.type = 'month';
  monthInput.min = '1000-01'; monthInput.max = '9998-12'; monthInput.required = true;
  monthInput.setAttribute('aria-describedby', 'indicadores-supervisor-filtro');
  field.appendChild(label); field.appendChild(monthInput); form.appendChild(field);
  var button = element('button', 'btn btn-outline-primary', 'Consultar mes');
  button.type = 'submit'; form.appendChild(button); root.appendChild(form);
  var filterHelp = element('p', 'small text-muted mb-2', 'Este filtro solo cambia las tarjetas.');
  filterHelp.id = 'indicadores-supervisor-filtro'; root.appendChild(filterHelp);
  var status = element('p', 'text-muted small mb-3', 'Pendiente de consulta.');
  status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite'); root.appendChild(status);
  var grid = element('div', 'row g-3');
  definitions.forEach(function (definition) {
    var column = element('div', 'col-12 col-sm-6 col-lg-4 col-xl');
    var card = element('div', 'card h-100 shadow-sm border-2 border-' + definition[3]);
    var body = element('div', 'card-body');
    var header = element('div', 'd-flex align-items-center justify-content-between gap-2 mb-2');
    header.appendChild(element('h3', 'h6 fw-bold mb-0', definition[1]));
    var icon = element('i', 'bi ' + definition[4] + ' text-' + definition[3] + ' fs-4');
    icon.setAttribute('aria-hidden', 'true'); header.appendChild(icon); body.appendChild(header);
    var value = element('p', 'display-6 fw-bold mb-2', '—');
    values.push(value); body.appendChild(value);
    body.appendChild(element('p', 'small text-muted mb-0', definition[2]));
    card.appendChild(body); column.appendChild(card); grid.appendChild(column);
  });
  root.appendChild(grid);
  root.appendChild(element('p', 'small text-muted mt-3 mb-0', 'Las tarjetas se superponen: una reserva aprobada puede ser urgente o normal. No sumes las cinco tarjetas. Urgentes y normales incluyen APROBADA, PENDIENTE, RECHAZADA y CANCELADA; usuarios activos es un conteo actual independiente.'));
  async function load() {
    var ticket = ++sequence;
    if (currentController) currentController.abort();
    clearValues();
    var requestedMonth = monthInput.value;
    if (!validMonth(requestedMonth)) { disableControls(false); setMessage('Elige un mes entre 1000-01 y 9998-12.', true); return; }
    disableControls(true);
    setMessage('Consultando ' + requestedMonth + '. Las cifras anteriores se han retirado.', false);
    var timer, timedOut = false;
    try {
      var url = apiUrl(); url.searchParams.set('mes', requestedMonth);
      var controller = new AbortController(); currentController = controller;
      timer = window.setTimeout(function () { timedOut = true; controller.abort(); }, 30000);
      var response = await fetch(url.href, {method: 'GET', credentials: 'same-origin', cache: 'no-store', headers: {'Accept': 'application/json'}, signal: controller.signal, redirect: 'error'});
      if (ticket !== sequence) return;
      if (!response.ok) { setMessage(httpMessage(response.status), true); return; }
      var contentType = (response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
      if (response.redirected || contentType !== 'application/json') throw new Error('INVALID_RESPONSE_TYPE');
      var data = await response.json();
      if (ticket !== sequence) return;
      if (!validateBody(data, requestedMonth)) throw new Error('INVALID_RESPONSE');
      definitions.forEach(function (definition, index) { values[index].textContent = String(data[definition[0]]); });
      setMessage('Mes consultado: ' + requestedMonth + '. Usuarios activos muestra el conteo actual, no el mes seleccionado.', false);
    } catch (error) {
      if (ticket !== sequence) return;
      clearValues();
      setMessage(timedOut ? 'La consulta superó 30 segundos. No se muestran cifras anteriores. Intenta de nuevo.' : 'No fue posible validar o consultar los indicadores. Comprueba tu sesión y vuelve a consultar.', true);
    } finally {
      if (timer !== undefined) window.clearTimeout(timer);
      if (ticket === sequence) { currentController = null; disableControls(false); }
    }
  }
  form.addEventListener('submit', function (event) { event.preventDefault(); load(); });
  try { monthInput.value = monthInMexicoCity(); load(); }
  catch (error) { clearValues(); setMessage('No pude determinar el mes de Ciudad de México. Selecciona el mes y pulsa Consultar mes.', true); }
}());
