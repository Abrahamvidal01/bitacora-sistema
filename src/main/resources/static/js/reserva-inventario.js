document.addEventListener("DOMContentLoaded", () => {
    const modal = document.getElementById("modalNuevaReserva");
    if (!modal) return;

    const selector = modal.querySelector("#selectEspacio");
    const mensaje = modal.querySelector("#msgSinEspacio");
    const contenedor = modal.querySelector("#gridInventario");
    const formulario = modal.querySelector("#formReservaDirecta");

    if (!selector || !mensaje || !contenedor || !formulario) return;

    // Se conserva mientras el guardado definitivo no esté habilitado.
    formulario.addEventListener("submit", evento => {
        evento.preventDefault();
    });

    const estadosPermitidos = new Set(["BUENO", "REGULAR"]);
    const estadosConocidos = new Set(["BUENO", "REGULAR", "MALO"]);
    let numeroConsulta = 0;

    function elemento(etiqueta, clase = "", texto) {
        const nodo = document.createElement(etiqueta);
        nodo.className = clase;

        if (texto !== undefined) {
            nodo.textContent = texto;
        }

        return nodo;
    }

    function prepararArticulos(datos) {
        if (!Array.isArray(datos)) {
            throw new Error("El inventario no es una lista");
        }

        const ids = new Set();

        return datos.map(articulo => {
            if (!articulo
                || !Number.isSafeInteger(articulo.id)
                || articulo.id <= 0
                || ids.has(articulo.id)
                || typeof articulo.nombreItem !== "string"
                || !articulo.nombreItem.trim()
                || !Number.isSafeInteger(articulo.cantidad)
                || articulo.cantidad < 0
                || (articulo.descripcion != null
                    && typeof articulo.descripcion !== "string")) {
                throw new Error("Registro de inventario inválido");
            }

            ids.add(articulo.id);

            return {
                id: articulo.id,
                nombre: articulo.nombreItem.trim(),
                descripcion: (articulo.descripcion ?? "").trim(),
                cantidad: articulo.cantidad,
                estado: typeof articulo.estadoBien === "string"
                    ? articulo.estadoBien.trim().toUpperCase()
                    : ""
            };
        });
    }

    function sumar(articulos) {
        const total = articulos.reduce(
            (acumulado, articulo) => acumulado + articulo.cantidad,
            0
        );

        if (!Number.isSafeInteger(total)) {
            throw new Error("Cantidad total fuera de rango");
        }

        return total;
    }

    function crearFila(articulo, espacioId) {
        const fila = elemento(
            "div",
            "border-top pt-3 mt-3"
        );

        const referencia =
            `${articulo.estado || "SIN ESTADO"} · Registro #${articulo.id}`;

        const reservable =
            estadosPermitidos.has(articulo.estado)
            && articulo.cantidad > 0;

        if (!reservable) {
            fila.append(
                elemento(
                    "p",
                    "small fw-semibold text-danger mb-0",
                    `${referencia}: ${articulo.cantidad} unidades. `
                    + "No seleccionable."
                )
            );

            return fila;
        }

        const idBase =
            `recurso-${espacioId}-${articulo.id}`;

        const seleccion = elemento("div", "form-check");

        const casilla = elemento("input", "form-check-input");
        casilla.type = "checkbox";
        casilla.id = idBase;
        casilla.name = "articulosIds";
        casilla.value = String(articulo.id);

        const etiqueta = elemento(
            "label",
            "form-check-label fw-semibold",
            `${referencia} · Existencias: ${articulo.cantidad}`
        );
        etiqueta.htmlFor = idBase;

        seleccion.append(casilla, etiqueta);

        const etiquetaCantidad = elemento(
            "label",
            "form-label small mt-2 mb-1",
            `Cantidad de ${articulo.nombre} en estado ${articulo.estado}`
        );
        etiquetaCantidad.htmlFor = `${idBase}-cantidad`;

        const cantidad = elemento(
            "input",
            "form-control form-control-sm"
        );
        cantidad.type = "number";
        cantidad.id = `${idBase}-cantidad`;
        cantidad.name = `cantidades[${articulo.id}]`;
        cantidad.min = "1";
        cantidad.max = String(articulo.cantidad);
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

        fila.append(seleccion, etiquetaCantidad, cantidad);
        return fila;
    }

    function crearTarjeta(articulos, espacioId) {
        const primero = articulos[0];
        const total = sumar(articulos);
        const reservables = sumar(
            articulos.filter(a => estadosPermitidos.has(a.estado))
        );

        const columna = elemento("div", "col-md-6");
        const tarjeta = elemento(
            "section",
            "border rounded p-3 h-100 bg-white shadow-sm"
        );

        tarjeta.append(
            elemento("h6", "fw-bold mb-1", primero.nombre),
            elemento(
                "p",
                "small text-muted mb-2",
                primero.descripcion || "Sin descripción"
            ),
            elemento(
                "p",
                "small fw-semibold mb-2",
                `Total registrado: ${total} · `
                + `En estados permitidos: ${reservables}`
            )
        );

        const desglose = elemento(
            "div",
            "small bg-light border rounded p-2"
        );

        for (const estado of estadosConocidos) {
            const cantidad = sumar(
                articulos.filter(a => a.estado === estado)
            );

            desglose.append(
                elemento(
                    "div",
                    estado === "MALO" ? "text-danger" : "",
                    `${estado}: ${cantidad}`
                )
            );
        }

        const desconocidos = articulos.filter(
            a => !estadosConocidos.has(a.estado)
        );

        if (desconocidos.length > 0) {
            desglose.append(
                elemento(
                    "div",
                    "text-danger",
                    `Estado no reconocido: ${sumar(desconocidos)}. `
                    + "Debe revisarse en inventario."
                )
            );
        }

        tarjeta.append(desglose);

        for (const articulo of articulos) {
            tarjeta.append(crearFila(articulo, espacioId));
        }

        columna.append(tarjeta);
        return columna;
    }

    async function cargarInventario() {
        const consultaActual = ++numeroConsulta;
        const espacioId = selector.value;

        contenedor.replaceChildren();
        contenedor.classList.add("d-none");

        const url = selector.selectedOptions[0]?.dataset.inventarioUrl;

        if (!espacioId || !url) {
            mensaje.textContent =
                "Selecciona un espacio para consultar su inventario.";
            return;
        }

        mensaje.textContent = "Consultando inventario…";

        try {
            const respuesta = await fetch(url, {
                credentials: "same-origin",
                cache: "no-store",
                headers: { "Accept": "application/json" }
            });

            const tipo = respuesta.headers.get("content-type") || "";

            if (!respuesta.ok || !tipo.includes("application/json")) {
                throw new Error("Respuesta de inventario no válida");
            }

            const datos = await respuesta.json();

            if (consultaActual !== numeroConsulta) return;

            const articulos = prepararArticulos(datos);

            if (articulos.length === 0) {
                mensaje.textContent =
                    "Este espacio no tiene inventario registrado.";
                return;
            }

            // Solo agrupa la presentación, nunca los IDs enviados.
            // Nombre y descripción se comparan exactamente tras quitar
            // espacios al principio y al final.
            const grupos = new Map();

            for (const articulo of articulos) {
                const clave = JSON.stringify([
                    articulo.nombre,
                    articulo.descripcion
                ]);

                if (!grupos.has(clave)) {
                    grupos.set(clave, []);
                }

                grupos.get(clave).push(articulo);
            }

            const fragmento = document.createDocumentFragment();

            for (const grupo of grupos.values()) {
                fragmento.append(crearTarjeta(grupo, espacioId));
            }

            contenedor.replaceChildren(fragmento);
            contenedor.classList.remove("d-none");

            mensaje.textContent =
                "Selecciona cada registro e indica su cantidad. "
                + "Las existencias mostradas no constituyen un apartado.";
        } catch (error) {
            if (consultaActual !== numeroConsulta) return;

            contenedor.replaceChildren();
            contenedor.classList.add("d-none");
            mensaje.textContent =
                "No se pudo cargar un inventario válido. "
                + "Comprueba tu sesión y revisa sus registros.";
        }
    }

    selector.addEventListener("change", cargarInventario);

    formulario.addEventListener("reset", () => {
        queueMicrotask(cargarInventario);
    });

    if (selector.value) {
        cargarInventario();
    }
});