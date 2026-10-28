document.addEventListener("DOMContentLoaded", () => {
    const modal = document.getElementById("modalNuevaReserva");

    if (!modal) return;

    const selector = modal.querySelector("#selectEspacio");
    const mensaje = modal.querySelector("#msgSinEspacio");
    const contenedor = modal.querySelector("#gridInventario");
    const formulario = modal.querySelector("form");

    if (!selector || !mensaje || !contenedor) return;

    // Temporal: aún no existe el guardado de reservas.
    formulario?.addEventListener("submit", evento => {
        evento.preventDefault();
    });

    let numeroConsulta = 0;

    selector.addEventListener("change", async () => {
        const consultaActual = ++numeroConsulta;

        // Elimina artículos y cantidades del espacio anterior.
        contenedor.replaceChildren();
        contenedor.classList.add("d-none");

        const opcion = selector.selectedOptions[0];
        const url = opcion?.dataset.inventarioUrl;

        if (!selector.value || !url) {
            mensaje.textContent =
                "Selecciona un espacio para consultar su inventario.";
            return;
        }

        mensaje.textContent = "Consultando inventario…";

        try {
            const respuesta = await fetch(url, {
                credentials: "same-origin",
                headers: {
                    "Accept": "application/json"
                }
            });

            const tipoContenido =
                respuesta.headers.get("content-type") || "";

            if (!respuesta.ok ||
                !tipoContenido.includes("application/json")) {
                throw new Error("Respuesta de inventario no válida");
            }

            const articulos = await respuesta.json();

            // Ignora respuestas antiguas si cambió el espacio.
            if (consultaActual !== numeroConsulta) return;

            if (!Array.isArray(articulos)) {
                throw new Error("Formato de inventario no válido");
            }

            if (articulos.length === 0) {
                mensaje.textContent =
                    "Este espacio no tiene inventario registrado.";
                return;
            }

            articulos.forEach(articulo => {
                const disponibles = Number(articulo.cantidad);
                const tieneExistencias =
                    Number.isInteger(disponibles) &&
                    disponibles > 0;

                const columna = document.createElement("div");
                columna.className = "col-md-6";

                const tarjeta = document.createElement("div");
                tarjeta.className =
                    "border rounded bg-white p-3 h-100";

                const seleccion = document.createElement("div");
                seleccion.className = "form-check mb-2";

                const casilla = document.createElement("input");
                casilla.type = "checkbox";
                casilla.className = "form-check-input";
                casilla.id = `reserva-articulo-${articulo.id}`;
                casilla.name = "articulosIds";
                casilla.value = String(articulo.id);
                casilla.disabled = !tieneExistencias;

                const etiqueta = document.createElement("label");
                etiqueta.className = "form-check-label fw-bold";
                etiqueta.htmlFor = casilla.id;
                etiqueta.textContent = articulo.nombreItem;

                seleccion.append(casilla, etiqueta);

                const detalle = document.createElement("p");
                detalle.className = "small text-muted mb-2";
                detalle.textContent =
                    `Existencias: ${articulo.cantidad} · ` +
                    `Estado: ${articulo.estadoBien || "Sin especificar"}`;

                const etiquetaCantidad =
                    document.createElement("label");

                etiquetaCantidad.className = "form-label small";
                etiquetaCantidad.htmlFor =
                    `reserva-cantidad-${articulo.id}`;

                etiquetaCantidad.textContent =
                    "Cantidad solicitada";

                const cantidad = document.createElement("input");
                cantidad.type = "number";
                cantidad.className = "form-control";
                cantidad.id = etiquetaCantidad.htmlFor;
                cantidad.name = `cantidades[${articulo.id}]`;
                cantidad.min = "1";
                cantidad.max = tieneExistencias
                    ? String(disponibles)
                    : "1";
                cantidad.step = "1";
                cantidad.value = "1";
                cantidad.required = true;
                cantidad.disabled = true;

                casilla.addEventListener("change", () => {
                    cantidad.disabled = !casilla.checked;

                    if (!casilla.checked) {
                        cantidad.value = "1";
                    }
                });

                tarjeta.append(
                    seleccion,
                    detalle,
                    etiquetaCantidad,
                    cantidad
                );

                columna.append(tarjeta);
                contenedor.append(columna);
            });

            mensaje.textContent =
                "Marca los artículos que necesitas e indica la cantidad.";

            contenedor.classList.remove("d-none");

        } catch (error) {
            if (consultaActual !== numeroConsulta) return;

            contenedor.replaceChildren();
            contenedor.classList.add("d-none");

            mensaje.textContent =
                "No se pudo consultar el inventario. " +
                "Comprueba tu sesión e inténtalo nuevamente.";
        }
    });
});