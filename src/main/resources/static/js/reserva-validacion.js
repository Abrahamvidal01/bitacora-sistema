document.addEventListener("DOMContentLoaded", () => {
    const modal = document.getElementById("modalNuevaReserva");
    const formulario = modal?.querySelector("#formReservaDirecta");
    const boton = modal?.querySelector("#btnComprobarReserva");
    const confirmar = modal?.querySelector("#btnConfirmarReserva");
    const resultado = modal?.querySelector("#resultadoValidacionReserva");
    if (!modal || !formulario || !boton || !resultado) return;

    const espacio = formulario.querySelector("#selectEspacio");
    const correo = formulario.querySelector("#reservaDirectaCorreo");
    const fecha = formulario.querySelector("#reservaDirectaFecha");
    const inicio = formulario.querySelector("#reservaDirectaHoraInicio");
    const fin = formulario.querySelector("#reservaDirectaHoraFin");
    const motivo = formulario.querySelector("#reservaDirectaMotivo");
    const externos = formulario.querySelector("#reservaDirectaRecursosExternos");
    const oficio = formulario.querySelector("#reservaDirectaOficio");
    const requisicion = formulario.querySelector("#reservaDirectaRequisicion");
    const avisoExternos = formulario.querySelector("#avisoRecursosExternos");
    const urgencia = formulario.querySelector("#reservaDirectaUrgencia");
    const motivoUrgencia = formulario.querySelector("#reservaDirectaMotivoUrgencia");
    const bloqueUrgencia = formulario.querySelector("#bloqueMotivoUrgencia");
    const avisoModalidad = formulario.querySelector("#avisoModalidadReserva");

    if (!espacio || !correo || !fecha || !inicio || !fin || !motivo
        || !externos || !oficio || !requisicion || !urgencia
        || !motivoUrgencia || !bloqueUrgencia || !avisoModalidad) return;

    const MAX_BYTES = 3_000_000;
    const textoBoton = boton.textContent.trim();
    let numeroConsulta = 0;
    let solicitudPendiente = null;
    let descartarAlCerrar = false;
    if (confirmar) confirmar.disabled = true;

    function mostrarMensaje(texto, tipo = "warning") {
        resultado.className = `alert alert-${tipo} mt-3 mb-0`;
        resultado.textContent = texto;
        resultado.hidden = false;
        resultado.focus();
    }

    function invalidarResultado() {
        numeroConsulta++;
        solicitudPendiente?.abort();
        solicitudPendiente = null;
        boton.disabled = false;
        boton.textContent = textoBoton;
        formulario.removeAttribute("aria-busy");
        resultado.hidden = true;
        resultado.textContent = "";
        if (confirmar) confirmar.disabled = true;
    }

    function actualizarModalidad() {
        const hayExternos = externos.value.trim() !== "";
        const hayRequisicion = (requisicion.files?.length || 0) > 0;
        bloqueUrgencia.hidden = !urgencia.checked;
        motivoUrgencia.disabled = !urgencia.checked;
        motivoUrgencia.required = urgencia.checked;
        motivoUrgencia.setCustomValidity("");
        if (avisoExternos) avisoExternos.hidden = !hayExternos;
        if (!urgencia.checked) {
            avisoModalidad.textContent = "Modalidad normal: mínimo 72 horas completas de anticipación. "
                + "Si hay recursos fuera del inventario escolar o una requisición, se requerirá revisión del supervisor.";
        } else if (hayExternos || hayRequisicion) {
            avisoModalidad.textContent = "Urgencia con recursos externos o requisición: mínimo 72 horas completas. "
                + "Requiere revisión del supervisor; adjuntar un PDF no equivale a una aprobación.";
        } else {
            avisoModalidad.textContent = "Urgencia sin recursos externos ni requisición: mínimo 24 horas completas. "
                + "Requiere oficio de reservación y sigue sujeta a disponibilidad, limpieza y cupo.";
        }
    }

    function limpiarFormularioAlCerrar() {
        invalidarResultado();
        formulario.reset();
        for (const campo of [espacio, correo, fecha, inicio, fin,
            motivo, externos, oficio, requisicion, motivoUrgencia]) {
            campo.value = "";
            campo.setCustomValidity("");
        }
        urgencia.checked = false;
        formulario.classList.remove("was-validated");
        formulario.querySelectorAll(".is-valid, .is-invalid").forEach(campo => {
            campo.classList.remove("is-valid", "is-invalid");
        });
        actualizarModalidad();
        espacio.dispatchEvent(new Event("change", { bubbles: true }));
        const cuerpoModal = modal.querySelector(".modal-body");
        if (cuerpoModal) cuerpoModal.scrollTop = 0;
    }

    formulario.addEventListener("input", invalidarResultado);
    formulario.addEventListener("change", invalidarResultado);
    formulario.addEventListener("reset", () => {
        invalidarResultado();
        queueMicrotask(actualizarModalidad);
    });
    externos.addEventListener("input", actualizarModalidad);
    externos.addEventListener("change", actualizarModalidad);
    urgencia.addEventListener("change", actualizarModalidad);
    requisicion.addEventListener("change", actualizarModalidad);
    motivoUrgencia.addEventListener("input", () => motivoUrgencia.setCustomValidity(""));

    formulario.querySelector("#btnCancelarReserva")?.addEventListener("click", () => {
        descartarAlCerrar = true;
    });
    modal.addEventListener("hidden.bs.modal", () => {
        if (descartarAlCerrar) limpiarFormularioAlCerrar();
        else invalidarResultado();
        descartarAlCerrar = false;
    });
    formulario.addEventListener("submit", evento => {
        evento.preventDefault();
        evento.stopImmediatePropagation();
        mostrarMensaje("El guardado de reservas todavía no está habilitado. "
            + "Usa Comprobar datos para la validación parcial.");
    }, true);

    function recogerDocumento(campo, etiqueta) {
        const archivos = campo.files;
        if (!archivos || archivos.length === 0) return null;
        if (archivos.length !== 1) throw new Error(`${etiqueta}: selecciona un solo PDF.`);
        const archivo = archivos[0];
        if (!archivo.name.trim().toLowerCase().endsWith(".pdf")) {
            throw new Error(`${etiqueta}: solo se permiten archivos PDF.`);
        }
        if (archivo.size === 0) throw new Error(`${etiqueta}: el archivo está vacío.`);
        if (archivo.size > MAX_BYTES) {
            throw new Error(`${etiqueta}: el máximo es 3 MB por archivo (3,000,000 bytes).`);
        }
        return archivo;
    }

    boton.addEventListener("click", async () => {
        if (boton.disabled) return;
        actualizarModalidad();
        for (const campo of [espacio, correo, fecha, inicio, fin, motivo]) {
            if (!campo.reportValidity()) return;
        }
        if (urgencia.checked) {
            if (!motivoUrgencia.value.trim()) {
                motivoUrgencia.setCustomValidity("Indica el motivo de la atención por urgencia.");
            }
            if (!motivoUrgencia.reportValidity()) return;
        }
        const recursosSeleccionados = [];
        const casillas = formulario.querySelectorAll(
            'input[name="articulosIds"]:checked:not(:disabled)');
        for (const casilla of casillas) {
            const campoCantidad = formulario.elements.namedItem(`cantidades[${casilla.value}]`);
            if (!(campoCantidad instanceof HTMLInputElement) || campoCantidad.disabled) {
                mostrarMensaje("La selección de recursos es inconsistente. "
                    + "Vuelve a seleccionar el espacio.", "danger");
                return;
            }
            if (!campoCantidad.reportValidity()) return;
            recursosSeleccionados.push({id: casilla.value, cantidad: campoCantidad.value});
        }
        let archivoOficio;
        let archivoRequisicion;
        try {
            archivoOficio = recogerDocumento(oficio, "Oficio de reservación");
            archivoRequisicion = recogerDocumento(requisicion, "Requisición");
        } catch (error) {
            mostrarMensaje(error.message, "danger");
            return;
        }
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const nombreCabecera = document.querySelector('meta[name="_csrf_header"]')?.content;
        if (!token || !nombreCabecera) {
            mostrarMensaje("No se encontró la protección de sesión. "
                + "Recarga el panel o vuelve a iniciar sesión.", "danger");
            return;
        }
        const datosFormulario = new FormData();
        datosFormulario.append("espacioId", espacio.value);
        datosFormulario.append("correoSolicitante", correo.value.trim());
        datosFormulario.append("fechaEvento", fecha.value);
        datosFormulario.append("horaInicio", inicio.value);
        datosFormulario.append("horaFin", fin.value);
        datosFormulario.append("motivoEvento", motivo.value.trim());
        datosFormulario.append("recursosExternos", externos.value.trim());
        datosFormulario.append("esUrgencia", String(urgencia.checked));
        if (urgencia.checked) {
            datosFormulario.append("motivoUrgencia", motivoUrgencia.value.trim());
        }
        // No se envían horas mínimas, modalidad ni autorización del supervisor.
        for (const recurso of recursosSeleccionados) {
            datosFormulario.append("articulosIds", recurso.id);
            datosFormulario.append(`cantidades[${recurso.id}]`, recurso.cantidad);
        }
        if (archivoOficio) {
            datosFormulario.append("oficioReservacion", archivoOficio, archivoOficio.name);
        }
        if (archivoRequisicion) {
            datosFormulario.append("requisicion", archivoRequisicion, archivoRequisicion.name);
        }
        const consultaActual = ++numeroConsulta;
        solicitudPendiente?.abort();
        const controlador = new AbortController();
        solicitudPendiente = controlador;
        boton.disabled = true;
        boton.textContent = "Comprobando…";
        formulario.setAttribute("aria-busy", "true");
        resultado.hidden = true;
        try {
            const respuesta = await fetch(formulario.action, {
                method: "POST", credentials: "same-origin", cache: "no-store",
                signal: controlador.signal,
                headers: {"Accept": "application/json", [nombreCabecera]: token},
                body: datosFormulario
            });
            if (consultaActual !== numeroConsulta) return;
            const tipoContenido = respuesta.headers.get("content-type") || "";
            if (respuesta.status === 413 && !tipoContenido.includes("json")) {
                mostrarMensaje("La carga supera el tamaño permitido. "
                    + "Cada PDF debe pesar como máximo 3 MB.", "danger");
                return;
            }
            if (respuesta.redirected || !tipoContenido.includes("json")) {
                mostrarMensaje("No se recibió la respuesta esperada. "
                    + "Comprueba que tu sesión siga activa "
                    + "y que ingresaste como administrador.", "danger");
                return;
            }
            const datos = await respuesta.json();
            if (consultaActual !== numeroConsulta) return;
            if (!datos || typeof datos !== "object" || Array.isArray(datos)) {
                mostrarMensaje("La respuesta de validación no es válida.", "danger");
                return;
            }
            if (!respuesta.ok) {
                let mensaje;
                if (typeof datos.mensaje === "string") mensaje = datos.mensaje;
                else if (respuesta.status === 413) mensaje = "Cada PDF debe pesar como máximo 3 MB.";
                else if (respuesta.status === 403) mensaje = "Acceso denegado o sesión no válida. Vuelve a iniciar sesión.";
                else mensaje = `No se pudo comprobar la solicitud (HTTP ${respuesta.status}).`;
                mostrarMensaje(mensaje, "danger");
                return;
            }
            if (datos.validacionInicialSuperada !== true || typeof datos.mensaje !== "string") {
                mostrarMensaje("La respuesta de validación no es válida.", "danger");
                return;
            }
            mostrarMensaje(datos.mensaje
                + ` Inicio comprobado: ${datos.inicio}.`
                + ` Fin: ${datos.fin}. Zona: ${datos.zonaHoraria}.`, "info");
        } catch (error) {
            if (consultaActual !== numeroConsulta || error.name === "AbortError") return;
            mostrarMensaje("No se pudo completar la comprobación. "
                + "Revisa la conexión y que la aplicación continúe encendida.", "danger");
        } finally {
            if (consultaActual === numeroConsulta) {
                solicitudPendiente = null;
                boton.disabled = false;
                boton.textContent = textoBoton;
                formulario.removeAttribute("aria-busy");
            }
        }
    });
    actualizarModalidad();
});
