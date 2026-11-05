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

    if (!espacio || !correo || !fecha || !inicio || !fin) return;

    let numeroConsulta = 0;
    let solicitudPendiente = null;
    const textoBoton = boton.textContent.trim();

    // La comprobación parcial nunca habilita el guardado.
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

    formulario.addEventListener("input", invalidarResultado);
    formulario.addEventListener("change", invalidarResultado);
    modal.addEventListener("hidden.bs.modal", invalidarResultado);

    // Impide el envío normal mientras no exista el guardado definitivo.
    formulario.addEventListener("submit", evento => {
        evento.preventDefault();
        evento.stopImmediatePropagation();

        mostrarMensaje(
            "El guardado de reservas todavía no está habilitado. "
            + "Usa Comprobar datos para la validación parcial."
        );
    }, true);

    boton.addEventListener("click", async () => {
        if (boton.disabled) return;

        // La comprobación sigue sin exigir motivo ni archivos.
        for (const campo of [espacio, correo, fecha, inicio, fin]) {
            if (!campo.reportValidity()) return;
        }

        const recursosSeleccionados = [];

        const casillas = formulario.querySelectorAll(
            'input[name="articulosIds"]:checked:not(:disabled)'
        );

        for (const casilla of casillas) {
            const campoCantidad = formulario.elements.namedItem(
                `cantidades[${casilla.value}]`
            );

            if (!(campoCantidad instanceof HTMLInputElement)
                || campoCantidad.disabled) {
                mostrarMensaje(
                    "La selección de recursos es inconsistente. "
                    + "Vuelve a seleccionar el espacio.",
                    "danger"
                );
                return;
            }

            if (!campoCantidad.reportValidity()) return;

            recursosSeleccionados.push({
                id: casilla.value,
                cantidad: campoCantidad.value
            });
        }

        const token = document.querySelector(
            'meta[name="_csrf"]'
        )?.content;

        const nombreCabecera = document.querySelector(
            'meta[name="_csrf_header"]'
        )?.content;

        if (!token || !nombreCabecera) {
            mostrarMensaje(
                "No se encontró la protección de sesión. "
                + "Recarga el panel o vuelve a iniciar sesión.",
                "danger"
            );
            return;
        }

        const consultaActual = ++numeroConsulta;

        solicitudPendiente?.abort();

        const controlador = new AbortController();
        solicitudPendiente = controlador;

        boton.disabled = true;
        boton.textContent = "Comprobando…";
        formulario.setAttribute("aria-busy", "true");
        resultado.hidden = true;

        const parametros = new URLSearchParams({
            espacioId: espacio.value,
            correoSolicitante: correo.value.trim(),
            fechaEvento: fecha.value,
            horaInicio: inicio.value,
            horaFin: fin.value
        });

        for (const recurso of recursosSeleccionados) {
            parametros.append("articulosIds", recurso.id);
            parametros.append(
                `cantidades[${recurso.id}]`,
                recurso.cantidad
            );
        }

        try {
            const respuesta = await fetch(formulario.action, {
                method: "POST",
                credentials: "same-origin",
                cache: "no-store",
                signal: controlador.signal,
                headers: {
                    "Accept": "application/json",
                    "Content-Type":
                        "application/x-www-form-urlencoded;charset=UTF-8",
                    [nombreCabecera]: token
                },
                body: parametros.toString()
            });

            if (consultaActual !== numeroConsulta) return;

            const tipoContenido =
                respuesta.headers.get("content-type") || "";

            if (respuesta.redirected || !tipoContenido.includes("json")) {
                mostrarMensaje(
                    "No se recibió la respuesta esperada. "
                    + "Comprueba que tu sesión siga activa "
                    + "y que ingresaste como administrador.",
                    "danger"
                );
                return;
            }

            const datos = await respuesta.json();

            if (consultaActual !== numeroConsulta) return;

            if (!datos || typeof datos !== "object"
                || Array.isArray(datos)) {
                mostrarMensaje(
                    "La respuesta de validación no es válida.",
                    "danger"
                );
                return;
            }

            if (!respuesta.ok) {
                const mensaje = typeof datos.mensaje === "string"
                    ? datos.mensaje
                    : respuesta.status === 403
                        ? "Acceso denegado o sesión no válida. "
                        + "Vuelve a iniciar sesión."
                        : "No se pudo comprobar la solicitud "
                        + `(HTTP ${respuesta.status}).`;

                mostrarMensaje(mensaje, "danger");
                return;
            }

            if (datos.validacionInicialSuperada !== true) {
                mostrarMensaje(
                    "La respuesta de validación no es válida.",
                    "danger"
                );
                return;
            }

            mostrarMensaje(
                datos.mensaje
                + ` Inicio comprobado: ${datos.inicio}.`
                + ` Fin: ${datos.fin}.`
                + ` Zona: ${datos.zonaHoraria}.`,
                "info"
            );
        } catch (error) {
            if (consultaActual !== numeroConsulta
                || error.name === "AbortError") {
                return;
            }

            mostrarMensaje(
                "No se pudo completar la comprobación. "
                + "Revisa la conexión y que la aplicación "
                + "continúe encendida.",
                "danger"
            );
        } finally {
            if (consultaActual === numeroConsulta) {
                solicitudPendiente = null;
                boton.disabled = false;
                boton.textContent = textoBoton;
                formulario.removeAttribute("aria-busy");
            }
        }
    });
});