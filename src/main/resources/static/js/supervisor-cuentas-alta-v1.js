/* Registro de cuentas v1. Sin reintentos de POST ni almacenamiento de credenciales. */
(() => {
    'use strict';
    const modal = document.getElementById('modalNuevoUsuario');
    const triggers = document.querySelectorAll('button[data-bs-target="#modalNuevoUsuario"]');
    if (!modal || triggers.length !== 1) return;
    const trigger = triggers[0];
    trigger.hidden = true;
    trigger.style.display = 'none';
    const outside = document.getElementById('cuentas-disponibilidad');
    const form = document.getElementById('cuentas-formulario');
    const fields = document.getElementById('cuentas-campos');
    const submit = document.getElementById('cuentas-enviar');
    const reload = document.getElementById('cuentas-recargar-catalogo');
    const message = document.getElementById('cuentas-mensaje');
    const name = document.getElementById('cuentas-nombre');
    const email = document.getElementById('cuentas-correo');
    const password = document.getElementById('cuentas-password');
    const department = document.getElementById('cuentas-departamento');
    const confirmRoles = document.getElementById('cuentas-confirmar-roles');
    const confirmPrivileges = document.getElementById('cuentas-confirmar-privilegios');
    if (![outside, form, fields, submit, reload, message, name, email, password,
        department, confirmRoles, confirmPrivileges].every(Boolean)) return;
    form.addEventListener('submit', onSubmit);
    let busy = false;
    let catalogBusy = false;
    let authBlocked = false;
    let uncertain = false; // Never reset by closing the modal or a catalog refresh.
    let catalogReady = false;
    let activeIds = new Set();
    let postUrl;
    let catalogUrl;
    let csrfHeader;
    let csrfToken;
    const uncertainText = 'No se pudo confirmar el resultado. No vuelvas a enviar el alta; verifica con la persona responsable si la cuenta quedó registrada antes de intentarlo de nuevo. Comunica el correo al gestor autorizado; este panel no dispone de una consulta para conciliar el resultado.';
    const roleInputs = Array.from(form.querySelectorAll('input[name="roles"]'));
    const allowedRoles = new Set(['SOLICITANTE', 'SUPERVISOR', 'ADMINISTRADOR']);
    function say(text, kind = 'info') {
        message.textContent = text;
        message.className = 'alert alert-' + kind;
    }
    function sync() {
        const blocked = busy || catalogBusy || authBlocked || uncertain || !catalogReady;
        fields.disabled = blocked;
        submit.disabled = blocked;
        reload.disabled = busy || catalogBusy || authBlocked || uncertain;
        modal.setAttribute('aria-busy', String(busy || catalogBusy));
        modal.querySelectorAll('[data-bs-dismiss="modal"]').forEach(button => { button.disabled = busy; });
    }
    function endpoint(raw, suffix) {
        if (!raw) throw new Error('config');
        const url = new URL(raw, window.location.href);
        if (url.origin !== window.location.origin || !['http:', 'https:'].includes(url.protocol)
            || url.username || url.password || url.search || url.hash
            || !url.pathname.endsWith(suffix)) throw new Error('config');
        return url.href;
    }
    function positiveId(value) { return typeof value === 'number' && Number.isSafeInteger(value) && value > 0; }
    function isJson(response) {
        return /^application\/json(?:\s*;|$)/i.test(response.headers.get('Content-Type') || '');
    }
    function unconfirmed() { uncertain = true; say(uncertainText, 'danger'); }
    function denied() {
        authBlocked = true;
        say('La sesión o los permisos no permiten continuar. No repitas el envío. Verifica la sesión y los permisos antes de volver a abrir el panel.', 'warning');
    }
    modal.addEventListener('hide.bs.modal', event => { if (busy) event.preventDefault(); });
    modal.addEventListener('hidden.bs.modal', () => {
        password.value = '';
        confirmRoles.checked = false;
        confirmPrivileges.checked = false;
        // uncertain/authBlocked intentionally survive modal close.
    });
    roleInputs.forEach(input => input.addEventListener('change', () => {
        confirmRoles.checked = false;
        confirmPrivileges.checked = false;
    }));
    async function loadCatalog() {
        if (busy || catalogBusy || authBlocked || uncertain) return;
        catalogBusy = true;
        catalogReady = false;
        activeIds = new Set();
        department.replaceChildren();
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = 'Selecciona un departamento';
        department.append(placeholder);
        sync();
        say('Consultando departamentos activos.');
        const controller = new AbortController();
        const timeout = window.setTimeout(() => controller.abort(), 15000);
        try {
            const response = await fetch(catalogUrl, {
                method: 'GET', headers: { Accept: 'application/json' },
                credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: controller.signal
            });
            if (response.status === 401 || response.status === 403) {
                authBlocked = true;
                trigger.hidden = true;
                trigger.style.display = 'none';
                outside.textContent = '';
                denied();
                return;
            }
            if (response.status !== 200 || !isJson(response)) throw new Error('catalog');
            const rows = await response.json();
            if (!Array.isArray(rows) || rows.length > 2000) throw new Error('catalog');
            const ids = new Set();
            for (const row of rows) {
                if (!row || typeof row !== 'object' || Array.isArray(row) || !positiveId(row.id)
                    || typeof row.nombre !== 'string' || !row.nombre.trim() || row.nombre.length > 100
                    || ids.has(String(row.id))) throw new Error('catalog');
                ids.add(String(row.id));
            }
            for (const row of rows) {
                const option = document.createElement('option');
                option.value = String(row.id);
                option.textContent = row.nombre;
                department.append(option);
            }
            activeIds = ids;
            catalogReady = rows.length > 0;
            trigger.hidden = false;
            trigger.style.removeProperty('display');
            outside.textContent = '';
            say(catalogReady ? 'Completa los datos y revisa los roles antes de registrar.'
                : 'No hay departamentos activos disponibles. El alta permanece deshabilitada.', catalogReady ? 'info' : 'warning');
        } catch (_) {
            trigger.hidden = true;
            trigger.style.display = 'none';
            outside.textContent = 'El registro de cuentas no está disponible. Vuelve a abrir el panel después de verificar la sesión y la disponibilidad del servicio.';
            say('No se pudo consultar un catálogo válido. No se habilitó el alta.', 'warning');
        } finally {
            window.clearTimeout(timeout);
            catalogBusy = false;
            sync();
        }
    }
    async function onSubmit(event) {
        event.preventDefault();
        if (busy || catalogBusy || authBlocked || uncertain || !catalogReady) return;
        if (!form.reportValidity()) return;
        const cleanName = name.value.trim();
        const cleanEmail = email.value.trim();
        const selectedRoles = roleInputs.filter(input => input.checked).map(input => input.value);
        const elevated = selectedRoles.some(role => role === 'ADMINISTRADOR' || role === 'SUPERVISOR');
        if (!cleanName || cleanName.length > 150 || !cleanEmail || cleanEmail.length > 100
            || !activeIds.has(department.value) || selectedRoles.length < 1 || selectedRoles.length > 3
            || selectedRoles.some(role => !allowedRoles.has(role)) || new Set(selectedRoles).size !== selectedRoles.length
            || !confirmRoles.checked || (elevated && !confirmPrivileges.checked)) {
            say('Revisa nombre, correo, departamento, roles y las confirmaciones de privilegios.', 'warning');
            return;
        }
        if (Array.from(password.value).length < 12 || new TextEncoder().encode(password.value).length > 72) {
            say('La contraseña requiere al menos 12 caracteres Unicode y no puede exceder 72 bytes UTF-8.', 'warning');
            password.focus();
            return;
        }
        busy = true;
        sync();
        say('Registrando. No cierres esta página ni repitas el envío.');
        let params = new URLSearchParams();
        params.append('nombreCompleto', cleanName);
        params.append('correoInstitucional', cleanEmail);
        params.append('password', password.value);
        params.append('departamentoId', department.value);
        selectedRoles.forEach(role => params.append('roles', role));
        // Only five allowed fields; Thymeleaf's hidden CSRF input is not serialized.
        const headers = { Accept: 'application/json', 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' };
        headers[csrfHeader] = csrfToken;
        const controller = new AbortController();
        const timeout = window.setTimeout(() => controller.abort(), 30000);
        try {
            const response = await fetch(postUrl, {
                method: 'POST', headers, body: params, credentials: 'same-origin',
                cache: 'no-store', redirect: 'error', signal: controller.signal
            });
            if (response.status === 401 || response.status === 403) {
                denied();
            } else if (response.status === 201 && isJson(response)) {
                const result = await response.json();
                if (!result || !positiveId(result.usuarioId)) unconfirmed();
                else {
                    form.reset();
                    say('Cuenta registrada. ID ' + String(result.usuarioId) + '. No se creó un nombramiento. Los indicadores no se actualizan automáticamente.', 'success');
                }
            } else if (response.status === 400 && isJson(response)) {
                const result = await response.json();
                if (result && result.error === 'Solicitud invalida.')
                    say('El servidor rechazó los datos. Revísalos y escribe de nuevo la contraseña antes de enviar.', 'warning');
                else unconfirmed();
            } else {
                // Includes 409: integrity conflict is not necessarily a duplicate email.
                unconfirmed();
            }
        } catch (_) {
            // A timeout, redirect, malformed JSON or network error cannot prove rollback.
            unconfirmed();
        } finally {
            window.clearTimeout(timeout);
            password.value = '';
            confirmRoles.checked = false;
            confirmPrivileges.checked = false;
            params = null; // Best effort only; JavaScript strings cannot be reliably zeroized.
            busy = false;
            sync();
        }
    }
    reload.addEventListener('click', loadCatalog);
    try {
        if (!window.bootstrap || !window.bootstrap.Modal || !window.fetch || !window.TextEncoder
            || !window.AbortController || roleInputs.length !== 3) throw new Error('config');
        postUrl = endpoint(modal.dataset.cuentasAlta, '/supervisor/api/cuentas');
        catalogUrl = endpoint(modal.dataset.cuentasCatalogo, '/supervisor/api/cuentas/departamentos');
        csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
        csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
        if (!csrfHeader || !/^[A-Za-z0-9-]+$/.test(csrfHeader) || !csrfToken
            || csrfToken.includes('$' + '{')) throw new Error('config');
        loadCatalog();
    } catch (_) {
        authBlocked = true;
        sync();
        outside.textContent = 'El registro de cuentas no está disponible. Verifica la carga del panel y vuelve a abrirlo.';
    }
})();
