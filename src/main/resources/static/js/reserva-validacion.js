document.addEventListener("DOMContentLoaded", () => {
    const modal = document.getElementById("modalNuevaReserva");
    const formulario = modal?.querySelector("#formReservaDirecta");
    const boton = modal?.querySelector("#btnComprobarReserva");
    const confirmar = modal?.querySelector("#btnConfirmarReserva");
    const resultado = modal?.querySelector("#resultadoValidacionReserva");
    if (!modal || !formulario || !boton || !confirmar || !resultado) return;

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
    const cancelar = formulario.querySelector("#btnCancelarReserva");
    if (!espacio || !correo || !fecha || !inicio || !fin || !motivo || !externos
        || !oficio || !requisicion || !urgencia || !motivoUrgencia
        || !bloqueUrgencia || !avisoModalidad || !cancelar) return;

    const MAX_BYTES = 3_000_000;
    const textoBoton = boton.textContent.trim();
    let numeroConsulta = 0;
    let solicitudPendiente = null;
    let descartarAlCerrar = false;
    let comprobacionValida = false;
    let habilitadaEnServidor = false;
    let confirmando = false;
    // Conserva exactamente el mismo FormData (incluidos clave y archivos) al reintentar.
    let confirmacionPendiente = null;
    const controlesAnteriores = new Map();

    let urlComprobar;
    let urlConfirmar;
    let urlEstado;
    try {
        urlComprobar = new URL(formulario.action, window.location.href);
        if (urlComprobar.origin !== window.location.origin
            || !urlComprobar.pathname.endsWith("/validar-inicial")) return;
        urlConfirmar = new URL(urlComprobar);
        urlConfirmar.pathname = urlConfirmar.pathname.replace(/\/validar-inicial$/, "/confirmar");
        urlEstado = new URL(urlComprobar);
        urlEstado.pathname = urlEstado.pathname.replace(/\/validar-inicial$/, "/confirmacion/estado");
    } catch (_) {
        return;
    }

    function mostrarMensaje(texto, tipo = "warning") {
        resultado.className = `alert alert-${tipo} mt-3 mb-0`;
        resultado.textContent = texto;
        resultado.hidden = false;
        resultado.focus();
    }

    function actualizarBotones() {
        const pendiente = confirmacionPendiente !== null;
        confirmar.disabled = confirmando
            || (!pendiente && (!habilitadaEnServidor || !comprobacionValida));
        confirmar.textContent = confirmando ? "Guardando…"
            : pendiente ? "Reintentar misma confirmación" : "Confirmar Reserva";
        confirmar.title = pendiente
            ? "Reintenta sin cambiar los datos ni iniciar otra solicitud"
            : !habilitadaEnServidor
                ? "La confirmación está deshabilitada en el servidor"
                : !comprobacionValida
                    ? "Primero comprueba los datos; cualquier cambio invalida esa comprobación"
                    : "Guarda la reserva; el servidor volverá a comprobar todos los datos";
        boton.disabled = pendiente || solicitudPendiente !== null;
        cancelar.disabled = pendiente;
    }

    function invalidarResultado() {
        if (confirmacionPendiente !== null) return;
        numeroConsulta++;
        solicitudPendiente?.abort();
        solicitudPendiente = null;
        comprobacionValida = false;
        boton.textContent = textoBoton;
        formulario.removeAttribute("aria-busy");
        resultado.hidden = true;
        resultado.textContent = "";
        actualizarBotones();
    }

    function actualizarModalidad() {
        if (confirmacionPendiente !== null) return;
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
        if (confirmacionPendiente !== null) return;
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
        const cuerpo = modal.querySelector(".modal-body");
        if (cuerpo) cuerpo.scrollTop = 0;
    }

    function inmovilizarCampos() {
        controlesAnteriores.clear();
        formulario.querySelectorAll("input, select, textarea").forEach(campo => {
            controlesAnteriores.set(campo, campo.disabled);
            campo.disabled = true;
        });
    }

    function restaurarCampos() {
        controlesAnteriores.forEach((deshabilitado, campo) => {
            if (campo.isConnected) campo.disabled = deshabilitado;
        });
        controlesAnteriores.clear();
    }

    function cabeceras() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const nombre = document.querySelector('meta[name="_csrf_header"]')?.content;
        if (!token || !nombre) throw new Error("No se encontró la protección de sesión. Recarga el panel o vuelve a iniciar sesión.");
        return {"Accept": "application/json", [nombre]: token};
    }

    function recogerDocumento(campo, etiqueta) {
        const archivos = campo.files;
        if (!archivos || archivos.length === 0) return null;
        if (archivos.length !== 1) throw new Error(`${etiqueta}: selecciona un solo PDF.`);
        const archivo = archivos[0];
        if (!archivo.name.trim().toLowerCase().endsWith(".pdf")) throw new Error(`${etiqueta}: solo se permiten archivos PDF.`);
        if (archivo.size === 0) throw new Error(`${etiqueta}: el archivo está vacío.`);
        if (archivo.size > MAX_BYTES) throw new Error(`${etiqueta}: el máximo es 3 MB por archivo (3,000,000 bytes).`);
        return archivo;
    }

    function recogerFormulario() {
        actualizarModalidad();
        for (const campo of [espacio, correo, fecha, inicio, fin, motivo]) {
            if (!campo.reportValidity()) return null;
        }
        if (urgencia.checked) {
            if (!motivoUrgencia.value.trim()) motivoUrgencia.setCustomValidity("Indica el motivo de la atención por urgencia.");
            if (!motivoUrgencia.reportValidity()) return null;
        }
        const datos = new FormData();
        datos.append("espacioId", espacio.value);
        datos.append("correoSolicitante", correo.value.trim());
        datos.append("fechaEvento", fecha.value);
        datos.append("horaInicio", inicio.value);
        datos.append("horaFin", fin.value);
        datos.append("motivoEvento", motivo.value.trim());
        datos.append("recursosExternos", externos.value.trim());
        datos.append("esUrgencia", String(urgencia.checked));
        if (urgencia.checked) datos.append("motivoUrgencia", motivoUrgencia.value.trim());
        for (const casilla of formulario.querySelectorAll('input[name="articulosIds"]:checked:not(:disabled)')) {
            const cantidad = formulario.elements.namedItem(`cantidades[${casilla.value}]`);
            if (!(cantidad instanceof HTMLInputElement) || cantidad.disabled) {
                throw new Error("La selección de recursos es inconsistente. Vuelve a seleccionar el espacio.");
            }
            if (!cantidad.reportValidity()) return null;
            datos.append("articulosIds", casilla.value);
            datos.append(`cantidades[${casilla.value}]`, cantidad.value);
        }
        const archivoOficio = recogerDocumento(oficio, "Oficio de reservación");
        const archivoRequisicion = recogerDocumento(requisicion, "Requisición");
        if (archivoOficio) datos.append("oficioReservacion", archivoOficio, archivoOficio.name);
        if (archivoRequisicion) datos.append("requisicion", archivoRequisicion, archivoRequisicion.name);
        return datos;
    }

    function nuevaClave() {
        if (window.crypto?.randomUUID) return window.crypto.randomUUID();
        if (!window.crypto?.getRandomValues) throw new Error("El navegador no permite generar la clave segura de confirmación.");
        const bytes = window.crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 15) | 64;
        bytes[8] = (bytes[8] & 63) | 128;
        const hex = Array.from(bytes, n => n.toString(16).padStart(2, "0")).join("");
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }

    function resultadoIncierto() {
        mostrarMensaje("No se pudo confirmar la respuesta del servidor. La reserva podría haberse guardado. "
            + "No cambies los datos, no recargues la página ni inicies otra solicitud. "
            + "Pulsa Reintentar misma confirmación: se enviará la misma clave para consultar o completar el resultado. "
            + `Clave: ${confirmacionPendiente?.get("claveConfirmacion") || ""}.`, "warning");
    }

    function cerrarIntentoFallido(mensaje) {
        confirmacionPendiente = null;
        confirmando = false;
        restaurarCampos();
        invalidarResultado();
        actualizarModalidad();
        mostrarMensaje(mensaje, "danger");
    }

    formulario.addEventListener("input", invalidarResultado);
    formulario.addEventListener("change", invalidarResultado);
    formulario.addEventListener("reset", evento => {
        if (confirmacionPendiente !== null) {
            evento.preventDefault();
            return;
        }
        invalidarResultado();
        queueMicrotask(actualizarModalidad);
    });
    externos.addEventListener("input", actualizarModalidad);
    externos.addEventListener("change", actualizarModalidad);
    urgencia.addEventListener("change", actualizarModalidad);
    requisicion.addEventListener("change", actualizarModalidad);
    motivoUrgencia.addEventListener("input", () => motivoUrgencia.setCustomValidity(""));
    cancelar.addEventListener("click", evento => {
        if (confirmacionPendiente !== null) {
            evento.preventDefault();
            evento.stopImmediatePropagation();
            return;
        }
        descartarAlCerrar = true;
    }, true);
    modal.addEventListener("hide.bs.modal", evento => {
        if (confirmacionPendiente !== null) evento.preventDefault();
    });
    modal.addEventListener("hidden.bs.modal", () => {
        if (confirmacionPendiente !== null) return;
        if (descartarAlCerrar) limpiarFormularioAlCerrar();
        else invalidarResultado();
        descartarAlCerrar = false;
    });
    window.addEventListener("beforeunload", evento => {
        if (confirmacionPendiente !== null) {
            evento.preventDefault();
            evento.returnValue = "";
        }
    });
    formulario.addEventListener("submit", evento => {
        evento.preventDefault();
        evento.stopImmediatePropagation();
        if (confirmacionPendiente !== null) return;
        mostrarMensaje("Usa Comprobar datos y después Confirmar Reserva. Pulsar Enter no guarda la solicitud.");
    }, true);

    boton.addEventListener("click", async () => {
        if (boton.disabled || confirmacionPendiente !== null) return;
        let datosFormulario;
        let headers;
        try {
            datosFormulario = recogerFormulario();
            if (!datosFormulario) return;
            headers = cabeceras();
        } catch (error) {
            mostrarMensaje(error.message, "danger");
            return;
        }
        comprobacionValida = false;
        const consultaActual = ++numeroConsulta;
        solicitudPendiente?.abort();
        const controlador = new AbortController();
        solicitudPendiente = controlador;
        boton.textContent = "Comprobando…";
        formulario.setAttribute("aria-busy", "true");
        resultado.hidden = true;
        actualizarBotones();
        try {
            const respuesta = await fetch(urlComprobar, {
                method: "POST", credentials: "same-origin", cache: "no-store",
                signal: controlador.signal, headers, body: datosFormulario
            });
            if (consultaActual !== numeroConsulta) return;
            const tipo = respuesta.headers.get("content-type") || "";
            if (respuesta.status === 413 && !tipo.includes("json")) {
                mostrarMensaje("La carga supera el tamaño permitido. Cada PDF debe pesar como máximo 3 MB.", "danger");
                return;
            }
            if (respuesta.redirected || !tipo.includes("json")) {
                mostrarMensaje("No se recibió la respuesta esperada. Comprueba que tu sesión de administrador siga activa.", "danger");
                return;
            }
            const datos = await respuesta.json();
            if (consultaActual !== numeroConsulta) return;
            if (!datos || typeof datos !== "object" || Array.isArray(datos)) throw new Error("Respuesta no válida");
            if (!respuesta.ok) {
                mostrarMensaje(typeof datos.mensaje === "string" ? datos.mensaje
                    : `No se pudo comprobar la solicitud (HTTP ${respuesta.status}).`, "danger");
                return;
            }
            if (datos.validacionInicialSuperada !== true || typeof datos.mensaje !== "string") throw new Error("Respuesta no válida");
            comprobacionValida = true;
            mostrarMensaje(datos.mensaje + ` Inicio comprobado: ${datos.inicio}.`
                + ` Fin: ${datos.fin}. Zona: ${datos.zonaHoraria}.`, "info");
        } catch (error) {
            if (consultaActual !== numeroConsulta || error.name === "AbortError") return;
            mostrarMensaje("No se pudo completar la comprobación. Revisa la conexión y que la aplicación continúe encendida.", "danger");
        } finally {
            if (consultaActual === numeroConsulta) {
                solicitudPendiente = null;
                boton.textContent = textoBoton;
                formulario.removeAttribute("aria-busy");
                actualizarBotones();
            }
        }
    });

    confirmar.addEventListener("click", async () => {
        if (confirmar.disabled || confirmando) return;
        let headers;
        try {
            headers = cabeceras();
            if (confirmacionPendiente === null) {
                if (!habilitadaEnServidor || !comprobacionValida) return;
                const datos = recogerFormulario();
                if (!datos) return;
                if (!window.confirm("Se guardará la reserva y se consumirá un cupo. "
                    + "Si incluye recursos externos o requisición quedará pendiente de revisión. ¿Continuar?")) return;
                datos.append("claveConfirmacion", nuevaClave());
                confirmacionPendiente = datos;
                numeroConsulta++;
                solicitudPendiente?.abort();
                solicitudPendiente = null;
                inmovilizarCampos();
            }
        } catch (error) {
            mostrarMensaje(error.message, "danger");
            return;
        }
        confirmando = true;
        formulario.setAttribute("aria-busy", "true");
        actualizarBotones();
        mostrarMensaje("Guardando la reserva. No cierres ni recargues esta página.", "info");
        try {
            // No se aborta una confirmación: abortar el navegador no revierte el servidor.
            const respuesta = await fetch(urlConfirmar, {
                method: "POST", credentials: "same-origin", cache: "no-store",
                headers, body: confirmacionPendiente
            });
            const tipo = respuesta.headers.get("content-type") || "";
            if (respuesta.redirected || !tipo.includes("json")) {
                resultadoIncierto();
                return;
            }
            const datos = await respuesta.json();
            if (!datos || typeof datos !== "object" || Array.isArray(datos)) {
                resultadoIncierto();
                return;
            }
            if (!respuesta.ok) {
                const rechazoDefinitivo = respuesta.status >= 400 && respuesta.status < 500
                    && respuesta.status !== 408 && respuesta.status !== 429;
                if (rechazoDefinitivo) {
                    cerrarIntentoFallido(typeof datos.mensaje === "string" ? datos.mensaje
                        : `El servidor rechazó la confirmación (HTTP ${respuesta.status}). Vuelve a comprobar los datos.`);
                } else resultadoIncierto();
                return;
            }
            if (!Number.isSafeInteger(datos.solicitudId) || datos.solicitudId <= 0
                || typeof datos.folio !== "string" || !datos.folio.trim()
                || !["APROBADA", "PENDIENTE"].includes(datos.estatus)
                || typeof datos.repetida !== "boolean" || typeof datos.mensaje !== "string") {
                resultadoIncierto();
                return;
            }
            const mensaje = `Reserva guardada. Folio: ${datos.folio}. Estado: ${datos.estatus}. `
                + (datos.repetida ? "Se recuperó una confirmación ya registrada; no se creó otra reserva. " : "")
                + datos.mensaje;
            confirmacionPendiente = null;
            confirmando = false;
            restaurarCampos();
            limpiarFormularioAlCerrar();
            mostrarMensaje(mensaje, "success");
        } catch (_) {
            resultadoIncierto();
        } finally {
            confirmando = false;
            formulario.removeAttribute("aria-busy");
            actualizarBotones();
        }
    });

    async function consultarHabilitacion() {
        try {
            const respuesta = await fetch(urlEstado, {
                credentials: "same-origin", cache: "no-store", headers: {"Accept": "application/json"}
            });
            if (!respuesta.ok || respuesta.redirected
                || !(respuesta.headers.get("content-type") || "").includes("json")) return;
            const datos = await respuesta.json();
            habilitadaEnServidor = datos?.habilitada === true;
        } catch (_) {
            habilitadaEnServidor = false;
        } finally {
            actualizarBotones();
        }
    }
    actualizarModalidad();
    actualizarBotones();
    consultarHabilitacion();
});