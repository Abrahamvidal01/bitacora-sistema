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

    // Nunca habilitar el guardado por superar esta comprobación parcial.
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

    // Evita el envío normal del formulario mientras no exista el guardado real.
    // Captura el evento antes de los manejadores de envío en fase de burbujeo.
    formulario.addEventListener("submit", evento => {
        evento.preventDefault();
        evento.stopImmediatePropagation();
        mostrarMensaje(
            "El guardado de reservas todavía no está habilitado. "
            + "Usa Comprobar datos para la validación inicial."
        );
    }, true);

    boton.addEventListener("click", async () => {
        if (boton.disabled) return;

        // No exige motivo, archivos ni cantidades para esta comprobación parcial.
        for (const campo of [espacio, correo, fecha, inicio, fin]) {
            if (!campo.reportValidity()) return;
        }

        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const nombreCabecera = document.querySelector('meta[name="_csrf_header"]')?.content;

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

        try {
            const respuesta = await fetch(formulario.action, {
                method: "POST",
                credentials: "same-origin",
                cache: "no-store",
                signal: controlador.signal,
                headers: {
                    "Accept": "application/json",
                    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                    [nombreCabecera]: token
                },
                body: parametros.toString()
            });

            if (consultaActual !== numeroConsulta) return;

            const tipoContenido = respuesta.headers.get("content-type") || "";
            if (respuesta.redirected || !tipoContenido.includes("json")) {
                mostrarMensaje(
                    "No se recibió la respuesta esperada. "
                    + "Comprueba que tu sesión siga activa y que ingresaste como administrador.",
                    "danger"
                );
                return;
            }

            const datos = await respuesta.json();
            if (consultaActual !== numeroConsulta) return;

            if (!respuesta.ok) {
                const mensaje = typeof datos.mensaje === "string"
                    ? datos.mensaje
                    : respuesta.status === 403
                        ? "Acceso denegado o sesión no válida. Vuelve a iniciar sesión."
                        : `No se pudo comprobar la solicitud (HTTP ${respuesta.status}).`;
                mostrarMensaje(mensaje, "danger");
                return;
            }

            if (datos.validacionInicialSuperada !== true) {
                mostrarMensaje("La respuesta de validación no es válida.", "danger");
                return;
            }

            mostrarMensaje(
                datos.mensaje
                + ` Inicio comprobado: ${datos.inicio}.`
                + ` Fin: ${datos.fin}. Zona: ${datos.zonaHoraria}.`,
                "info"
            );
        } catch (error) {
            if (consultaActual !== numeroConsulta || error.name === "AbortError") return;
            mostrarMensaje(
                "No se pudo completar la comprobación. "
                + "Revisa la conexión y que la aplicación continúe encendida.",
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
// Ejemplo de función en tu JS (ej. reserva-validacion.js) al presionar un botón de "Comprobar Disponibilidad"
function validarReservaInicial() {
    // 1. Recolectar los valores de tus inputs en la vista HTML
    const espacioId = document.getElementById('espacioId').value; // El select o input del espacio
    const correoSolicitante = document.getElementById('correoSolicitante').value;
    const fechaEvento = document.getElementById('fechaEvento').value;
    const horaInicio = document.getElementById('horaInicio').value;
    const horaFin = document.getElementById('horaFin').value;

    // 2. Empaquetar los datos como URLSearchParams (necesario para form-urlencoded)
    const formData = new URLSearchParams();
    formData.append('espacioId', espacioId);
    formData.append('correoSolicitante', correoSolicitante);
    formData.append('fechaEvento', fechaEvento);
    formData.append('horaInicio', horaInicio);
    formData.append('horaFin', horaFin);

    // 3. Enviar la petición POST al servidor
    fetch('/admin/reservas/validar-inicial', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: formData
    })
        .then(async response => {
            const data = await response.json();
            if (!response.ok) {
                // Si el servidor regresa 400 Bad Request o 409 Conflict (ej. espacio ocupado o sin 72h)
                throw new Error(data.mensaje || 'Error en la validación');
            }
            return data;
        })
        .then(data => {
            // ¡Éxito! La validación inicial pasó (espacio libre, 72h correctas, nombramiento válido)
            console.log("Validación superada:", data.mensaje);
            alert(data.mensaje);

            // Aquí puedes habilitar el siguiente paso de tu interfaz (ej. formulario de recursos)
        })
        .catch(error => {
            // Mostrar el mensaje de error directamente en la vista (ej. un div rojo de alerta)
            console.error("Error:", error.message);
            alert("Aviso: " + error.message);
        });
}