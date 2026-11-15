/* Revision v2. Independent module; no user-controlled HTML interpolation or old-script changes. */
(() => {
  'use strict';
  const root = document.getElementById('urgencias-supervisor'); if (!root) return;
  const api = root.dataset.api;
  const $ = id => document.getElementById(id);
  const message = text => { $('urg-mensaje').textContent = text; };
  const list = $('urg-lista');
  let pending = null, busy = false;
  const node = (tag,text) => { const e=document.createElement(tag); if(text!==undefined)e.textContent=String(text); return e; };
  const parts = new Intl.DateTimeFormat('en-CA',{timeZone:'America/Mexico_City',year:'numeric',month:'2-digit'}).formatToParts(new Date());
  $('urg-mes').value = parts.find(x=>x.type==='year').value+'-'+parts.find(x=>x.type==='month').value;
  function freeze() {
    $('urg-cargar').disabled = !!pending || busy; $('urg-mes').disabled = !!pending || busy;
    list.querySelectorAll('button[data-decision]').forEach(b=>b.disabled=!!pending||busy);
    $('urg-intento').hidden = !pending;
    $('urg-reintentar').disabled = busy;
    if(pending) $('urg-intento-texto').textContent = 'Operación conservada: '+pending.key+' — reserva '+pending.id+'. No cierres ni recargues esta pestaña mientras el resultado sea incierto. Si venció la sesión, autentícate en otra pestaña y reintenta aquí.';
  }
  async function json(response) { try { return await response.json(); } catch(e) { return null; } }
  async function load() {
    if(pending || busy)return;
    busy=true; freeze(); list.replaceChildren(); $('urg-conteos').textContent='';
    try {
      const r=await fetch(api+'?mes='+encodeURIComponent($('urg-mes').value),{credentials:'same-origin',cache:'no-store',redirect:'error'});
      const data=await json(r);
      if(!r.ok || !data || !Array.isArray(data.solicitudes)) throw new Error(data?.mensaje || 'No se pudo consultar. Comprueba activación, sesión y migración del entorno de pruebas.');
      $('urg-conteos').textContent='Total: '+data.total+' · Pendientes: '+data.pendientes+' · Aprobadas: '+data.aprobadas+' · Rechazadas: '+data.rechazadas;
      if(!data.solicitudes.length) list.append(node('p','No hay reservas urgentes para el mes consultado.'));
      for(const row of data.solicitudes) render(row);
      message('Consulta completa del mes. Los indicadores no incluyen NORMAL.');
    } catch(e) { message(e.message || 'Consulta no disponible.'); }
    finally { busy=false; freeze(); }
  }
  function render(row) {
    const card=node('article'); card.className='border rounded p-3 mb-3';
    card.append(node('h3',row.folio),
      node('p','Solicitante: '+row.solicitante+' · Departamento original: '+row.departamentoOriginal+' (#'+row.departamentoOriginalId+')'),
      node('p',row.fecha+' '+row.inicio+'–'+row.fin+' · Espacio: '+row.espacio+' (#'+row.espacioId+')'),
      node('p',row.modalidad+' · '+row.estado+' · Documentos: '+row.documentos+' · Revisión '+row.revision),node('p',row.motivo));
    for(const resource of row.recursos) card.append(node('p',resource.nombre+' · cantidad '+resource.cantidad+' · estado al confirmar '+resource.estadoHistorico));
    for(const type of ['oficio','requisicion']) {
      const a=node('a','Descargar '+type+' registrado'); a.href=api+'/'+row.id+'/documentos/'+type; a.className='d-inline-block me-3'; card.append(a);
    }
    const history=node('button','Consultar historial de revisión'); history.type='button';
    const output=node('pre'); output.style.whiteSpace='pre-wrap';
    history.addEventListener('click',async()=>{
      history.disabled=true;
      try {
        const r=await fetch(api+'/'+row.id+'/historial',{credentials:'same-origin',cache:'no-store',redirect:'error'}); const data=await json(r);
        if(!r.ok || !Array.isArray(data)) throw new Error(data?.mensaje || 'Historial no disponible');
        output.textContent=data.length?data.map(e=>'Revisión '+e.revision+' · UUID '+e.clave+'\n'+e.fecha+' · '+e.actor+' (#'+e.actorId+')\n'+e.accion+': '+e.estadoAntes+' → '+e.estadoDespues+' · Documentos '+e.documentosAntes+' → '+e.documentosDespues+'\n'+e.motivo+' · Reintegro: '+(e.reintegrado?'sí':'no')).join('\n\n'):'Sin revisiones registradas.';
      } catch(e) { output.textContent=e.message; } finally { history.disabled=false; }
    }); card.append(history,output);
    const reason=node('textarea'); reason.maxLength=255; reason.rows=2; reason.className='form-control my-2'; reason.placeholder='Motivo de la decisión u observación (obligatorio)';
    const reasonLabel=node('label','Motivo '); reasonLabel.append(reason);
    const reviewed=node('input'); reviewed.type='checkbox';
    const label=node('label',' He revisado manualmente los documentos registrados (o su ausencia documentada). '); label.prepend(reviewed);
    card.append(reasonLabel,label);
    const actions=row.estado==='PENDIENTE' && row.modalidad==='URGENTE_REQUISICION' ? ['APROBAR','RECHAZAR'] : row.estado==='APROBADA' ? ['VALIDAR_DOCUMENTOS','OBSERVAR_DOCUMENTOS'] : [];
    for(const action of actions) {
      const b=node('button',action.replaceAll('_',' ')); b.type='button'; b.dataset.decision='true'; b.className='btn btn-outline-primary m-1';
      b.addEventListener('click',()=>begin(row,action,reason.value,reviewed.checked)); card.append(b);
    }
    list.append(card);
  }
  function uuid() {
    if(!globalThis.crypto?.getRandomValues) throw new Error('No hay generador criptográfico disponible; operación bloqueada.');
    const b=new Uint8Array(16); crypto.getRandomValues(b); b[6]=(b[6]&15)|64; b[8]=(b[8]&63)|128;
    const h=[...b].map(v=>v.toString(16).padStart(2,'0')).join(''); return h.slice(0,8)+'-'+h.slice(8,12)+'-'+h.slice(12,16)+'-'+h.slice(16,20)+'-'+h.slice(20);
  }
  function begin(row,action,reason,reviewed) {
    if(pending || busy)return;
    if(!reason.trim() || reason.length>255){message('Indica un motivo de 1 a 255 caracteres.'); return;}
    if(action!=='OBSERVAR_DOCUMENTOS' && !reviewed){message('Confirma la revisión manual de los documentos.'); return;}
    if(!confirm(action+': '+row.folio+'\n'+reason+'\n'+(action==='RECHAZAR'?'El rechazo reintegra una sola vez el consumo histórico.':'La operación quedará auditada.')+'\n¿Confirmar?'))return;
    try {
      const key=uuid();
      pending=Object.freeze({id:row.id,key,body:JSON.stringify({clave:key,versionEsperada:row.version,accion:action,motivo:reason,documentosRevisados:reviewed})});
      freeze(); send();
    } catch(e) { message(e.message); }
  }
  async function send() {
    if(!pending || busy)return;
    busy=true; freeze();
    let postStarted=false;
    try {
      // Every first send AND retry obtains a token for the CURRENT authenticated session.
      // Never fall back to stale meta tags. UUID and serialized payload are not changed.
      const csrfResponse=await fetch(api+'/csrf',{credentials:'same-origin',cache:'no-store',redirect:'error'});
      const csrf=await json(csrfResponse);
      if(!csrfResponse.ok || !csrf || typeof csrf.headerName!=='string' || !csrf.headerName || typeof csrf.token!=='string' || !csrf.token) {
        throw new Error('No se obtuvo CSRF autenticado');
      }
      postStarted=true;
      const r=await fetch(api+'/'+pending.id+'/revision',{method:'POST',credentials:'same-origin',cache:'no-store',redirect:'error',headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token},body:pending.body});
      const data=await json(r);
      if(r.ok && data && data.solicitudId===pending.id && typeof data.repetida==='boolean') {
        const text=data.repetida?'Operación ya registrada; no se duplicó.':'Operación registrada.';
        pending=null; message(text); busy=false;freeze(); await load(); message(text);return;
      }
      // Only our own structured 400/409 responses guarantee a non-applied business rejection.
      if((r.status===400 || r.status===409) && data && data.resultadoIncierto===false && typeof data.mensaje==='string') {
        pending=null;message(data.mensaje+' Vuelve a consultar antes de una nueva decisión.'); list.replaceChildren(); $('urg-conteos').textContent='';
      } else message((data?.mensaje || 'Resultado incierto o sesión no disponible.')+' La clave y el contenido permanecen intactos. Si venció la sesión, autentícate en otra pestaña y reintenta aquí la misma operación.');
    } catch(e) {
      message(postStarted
        ? 'Resultado incierto por red o respuesta. La operación y su UUID siguen conservados. Autentícate en otra pestaña si hace falta y reintenta aquí, sin recargar.'
        : 'No se pudo obtener un CSRF de la sesión actual; no se envió este intento. La operación y su UUID permanecen intactos. Autentícate en otra pestaña y reintenta aquí, sin recargar.');
    } finally {busy=false;freeze();}
  }
  $('urg-cargar').addEventListener('click',load);
  $('urg-reintentar').addEventListener('click',send);
  window.addEventListener('beforeunload',e=>{if(pending){e.preventDefault();e.returnValue='';}});
  // No local/session storage of operational records or CSRF. If the warning is ignored, reconcile the displayed UUID against history before another decision.
  load();
})();