(() => {
    "use strict";
    document.addEventListener("DOMContentLoaded", () => {
        const $ = id => document.getElementById(id);
        const panel = $("dashboardAdminDatos");
        if (!panel) return;
        const ids = ["totalUsuariosActivos", "totalEspaciosActivos", "totalUrgenciasMes",
            "mesResumenAdmin", "estadoResumenAdmin", "btnActualizarDashboard",
            "modalHistorialReservas", "mesHistorialAdmin", "estadoHistorialAdmin",
            "cuerpoHistorialAdmin", "historialAnteriorAdmin", "historialSiguienteAdmin",
            "paginacionHistorialAdmin", "modalAgendaDisponibilidad", "selectEspacioAgenda",
            "inputFechaAgenda", "estadoAgendaAdmin", "cuerpoAgendaAdmin", "btnActualizarAgendaAdmin"];
        if (ids.some(id => !$(id))) {
            console.error("El HTML no coincide con dashboard-admin.js");
            return;
        }
        const base = new URL(panel.dataset.url.replace(/\/$/, "") + "/", location.href);
        if (base.origin !== location.origin) return;
        const canales = new Map();
        let pagina = 0, paginas = 1, mes = "", temporizador;
        const validoEntero = valor => Number.isSafeInteger(valor) && valor >= 0;
        const estado = (id, mensaje, error = false) => {
            $(id).textContent = mensaje;
            $(id).classList.toggle("text-danger", error);
            $(id).setAttribute("role", error ? "alert" : "status");
        };
        const celda = texto => {
            const td = document.createElement("td");
            td.textContent = texto == null ? "" : String(texto);
            return td;
        };
        const filaMensaje = (id, columnas, mensaje) => {
            const tr = document.createElement("tr"), td = celda(mensaje);
            td.colSpan = columnas;
            td.className = "text-center text-muted py-4";
            tr.append(td);
            $(id).replaceChildren(tr);
        };
        const consultar = async (canal, ruta, parametros, recibir, fallar) => {
            const previa = canales.get(canal);
            canales.delete(canal);
            previa?.abort();
            const control = new AbortController();
            canales.set(canal, control);
            try {
                const url = new URL(ruta, base);
                for (const [nombre, valor] of Object.entries(parametros)) {
                    if (valor !== "" && valor != null) url.searchParams.set(nombre, String(valor));
                }
                const respuesta = await fetch(url, {
                    credentials: "same-origin", cache: "no-store",
                    headers: { Accept: "application/json" }, signal: control.signal
                });
                if (respuesta.redirected || !(respuesta.headers.get("content-type") || "").includes("application/json")) {
                    throw new Error("La sesión terminó o la respuesta no es válida. Vuelve a iniciar sesión.");
                }
                const datos = await respuesta.json();
                if (!respuesta.ok) throw new Error(typeof datos?.mensaje === "string"
                    ? datos.mensaje : "No se pudo completar la consulta.");
                if (!control.signal.aborted && canales.get(canal) === control) recibir(datos);
            } catch (error) {
                if (error.name !== "AbortError" && canales.get(canal) === control)
                    fallar(error.message || "No se pudo completar la consulta.");
            } finally {
                if (canales.get(canal) === control) canales.delete(canal);
            }
        };
        const insignia = valor => {
            const nombres = {
                DISPONIBLE: ["bg-success", "Disponible"], OCUPADO: ["bg-danger", "Ocupado"],
                PENDIENTE: ["bg-warning text-dark", "Pendiente: sí bloquea"],
                APROBADA: ["bg-success", "Aprobada"], LIMPIEZA: ["bg-info text-dark", "Limpieza"],
                MARGEN_PREVIO: ["bg-secondary", "Margen previo"],
                NO_DISPONIBLE: ["bg-dark", "No disponible"],
                CANCELADA: ["bg-secondary", "Cancelada"], RECHAZADA: ["bg-danger", "Rechazada"]
            };
            const clave = typeof valor === "string" ? valor.trim().toUpperCase() : "";
            const [clase, nombre] = nombres[clave] || ["bg-dark", valor || "Requiere revisión"];
            const span = document.createElement("span");
            span.className = "badge " + clase;
            span.textContent = nombre;
            return span;
        };
        const hora = valor => {
            if (typeof valor !== "string") return "";
            const h = valor.includes("T") ? valor.split("T")[1] : valor;
            return /^\d{2}:\d{2}:00(?:\.0+)?$/.test(h) ? h.slice(0, 5) : h;
        };
        const horaAgenda = (valor, fecha) =>
            typeof valor === "string" && valor.split("T")[0] !== fecha
                && hora(valor) === "00:00" ? "24:00" : hora(valor);
        const cargarResumen = () => {
            ["totalUsuariosActivos", "totalEspaciosActivos", "totalUrgenciasMes"].forEach(id => $(id).textContent = "…");
            estado("estadoResumenAdmin", "Consultando datos reales…");
            $("btnActualizarDashboard").disabled = true;
            return consultar("resumen", "resumen", { mes }, datos => {
                if (![datos.usuariosActivos, datos.espaciosActivos, datos.urgenciasDelMes].every(validoEntero)
                    || typeof datos.mes !== "string" || typeof datos.fechaActual !== "string")
                    throw new Error("El resumen recibido no tiene un formato válido.");
                $("totalUsuariosActivos").textContent = datos.usuariosActivos;
                $("totalEspaciosActivos").textContent = datos.espaciosActivos;
                $("totalUrgenciasMes").textContent = datos.urgenciasDelMes;
                $("mesResumenAdmin").textContent = datos.mes;
                mes = datos.mes;
                if (!$("mesHistorialAdmin").value) $("mesHistorialAdmin").value = datos.mes;
                if (!$("inputFechaAgenda").value) $("inputFechaAgenda").value = datos.fechaActual;
                estado("estadoResumenAdmin", "Datos consultados. Activo no significa libre: puede estar en mantenimiento. El mes corresponde a la fecha del evento.");
                $("btnActualizarDashboard").disabled = false;
            }, mensaje => {
                ["totalUsuariosActivos", "totalEspaciosActivos", "totalUrgenciasMes"].forEach(id => $(id).textContent = "—");
                estado("estadoResumenAdmin", mensaje, true);
                $("btnActualizarDashboard").disabled = false;
            });
        };
        const cargarHistorial = () => {
            $("historialAnteriorAdmin").disabled = true;
            $("historialSiguienteAdmin").disabled = true;
            $("paginacionHistorialAdmin").textContent = "";
            estado("estadoHistorialAdmin", "Consultando urgencias…");
            filaMensaje("cuerpoHistorialAdmin", 7, "Cargando…");
            return consultar("historial", "historial", { mes: $("mesHistorialAdmin").value || mes, pagina }, datos => {
                if (!Array.isArray(datos.reservas) || !validoEntero(datos.total)
                    || !validoEntero(datos.pagina) || !validoEntero(datos.totalPaginas) || datos.totalPaginas < 1)
                    throw new Error("El historial recibido no tiene un formato válido.");
                pagina = datos.pagina;
                paginas = datos.totalPaginas;
                const filas = datos.reservas.map(r => {
                    const tr = document.createElement("tr"), titular = celda(r.solicitante);
                    const depto = document.createElement("small");
                    depto.className = "d-block text-muted";
                    depto.textContent = r.departamento || "";
                    titular.append(depto);
                    const tdEstado = document.createElement("td"), modalidad = document.createElement("small");
                    modalidad.className = "d-block text-muted";
                    modalidad.textContent = r.modalidad === "URGENTE_INVENTARIO" ? "Urgencia: mínimo 24 h"
                        : r.modalidad === "URGENTE_REQUISICION" ? "Urgencia: mínimo 72 h" : "Requiere revisión";
                    tdEstado.append(insignia(r.estado), modalidad);
                    tr.append(celda(r.folio), titular, celda(r.espacio), celda(r.fecha),
                        celda(hora(r.inicio) + " – " + hora(r.fin)), celda(r.registro), tdEstado);
                    return tr;
                });
                if (filas.length) $("cuerpoHistorialAdmin").replaceChildren(...filas);
                else filaMensaje("cuerpoHistorialAdmin", 7, "No hay reservas urgentes para este mes.");
                $("paginacionHistorialAdmin").textContent = "Página " + (pagina + 1) + " de " + paginas;
                $("historialAnteriorAdmin").disabled = pagina <= 0;
                $("historialSiguienteAdmin").disabled = pagina + 1 >= paginas;
                if (!$("mesHistorialAdmin").value) $("mesHistorialAdmin").value = datos.mes;
                estado("estadoHistorialAdmin", datos.total + " reserva(s) urgente(s). Pendiente significa guardada y en revisión; sí ocupa el espacio.");
            }, mensaje => {
                filaMensaje("cuerpoHistorialAdmin", 7, "No se pudo consultar el historial.");
                estado("estadoHistorialAdmin", mensaje, true);
            });
        };
        const cargarAgenda = () => {
            estado("estadoAgendaAdmin", "Consultando reservas y limpieza…");
            filaMensaje("cuerpoAgendaAdmin", 4, "Cargando…");
            return consultar("agenda", "agenda", {
                fecha: $("inputFechaAgenda").value, espacioId: $("selectEspacioAgenda").value
            }, datos => {
                if (!Array.isArray(datos.espacios) || typeof datos.fecha !== "string")
                    throw new Error("La agenda recibida no tiene un formato válido.");
                if (!$("inputFechaAgenda").value) $("inputFechaAgenda").value = datos.fecha;
                const filas = [];
                for (const e of datos.espacios) {
                    if (!Array.isArray(e.tramos)) throw new Error("Agenda de espacio inválida.");
                    for (const t of e.tramos) {
                        const tr = document.createElement("tr"), tdEstado = document.createElement("td");
                        tdEstado.append(insignia(t.estado));
                        tr.append(celda(horaAgenda(t.inicio, datos.fecha) + " – " + horaAgenda(t.fin, datos.fecha)),
                            celda(e.nombre), tdEstado, celda(t.detalle));
                        filas.push(tr);
                    }
                }
                if (filas.length) $("cuerpoAgendaAdmin").replaceChildren(...filas);
                else filaMensaje("cuerpoAgendaAdmin", 4, "No hay espacios para consultar.");
                estado("estadoAgendaAdmin", "Fecha " + datos.fecha + " · Zona " + datos.zonaHoraria
                    + ". Consulta informativa: no aparta horarios y la confirmación vuelve a comprobarlos.");
            }, mensaje => {
                filaMensaje("cuerpoAgendaAdmin", 4, "No se puede ofrecer disponibilidad con esta consulta.");
                estado("estadoAgendaAdmin", mensaje, true);
            });
        };
        const cargarEspacios = () => {
            const anterior = $("selectEspacioAgenda").value;
            $("selectEspacioAgenda").disabled = true;
            // Invalida una agenda pendiente antes de reconstruir el selector.
            const agendaAnterior = canales.get("agenda");
            canales.delete("agenda");
            agendaAnterior?.abort();
            estado("estadoAgendaAdmin", "Consultando espacios…");
            filaMensaje("cuerpoAgendaAdmin", 4, "Cargando…");
            return consultar("espacios", "espacios", {}, datos => {
                if (!Array.isArray(datos)) throw new Error("Selector de espacios inválido.");
                const todos = document.createElement("option");
                todos.value = "";
                todos.textContent = "-- Todos los espacios --";
                const opciones = [todos];
                for (const e of datos) {
                    if (!Number.isSafeInteger(e.id) || e.id <= 0 || typeof e.nombre !== "string"
                        || typeof e.activo !== "boolean") throw new Error("Datos de espacio inválidos.");
                    const opcion = document.createElement("option");
                    opcion.value = String(e.id);
                    opcion.textContent = e.nombre + (!e.activo ? " (desactivado)"
                        : e.estado !== "DISPONIBLE" ? " (" + (e.estado || "requiere revisión") + ")" : "");
                    opciones.push(opcion);
                }
                $("selectEspacioAgenda").replaceChildren(...opciones);
                if (opciones.some(o => o.value === anterior)) $("selectEspacioAgenda").value = anterior;
                $("selectEspacioAgenda").disabled = false;
                cargarAgenda();
            }, mensaje => {
                filaMensaje("cuerpoAgendaAdmin", 4, "La agenda no está disponible.");
                estado("estadoAgendaAdmin", mensaje, true);
            });
        };
        const refrescar = () => {
            cargarResumen();
            if ($("modalHistorialReservas").classList.contains("show")) cargarHistorial();
            if ($("modalAgendaDisponibilidad").classList.contains("show")) cargarEspacios();
        };
        $("btnActualizarDashboard").addEventListener("click", refrescar);
        $("modalHistorialReservas").addEventListener("show.bs.modal", cargarHistorial);
        $("modalAgendaDisponibilidad").addEventListener("show.bs.modal", cargarEspacios);
        $("mesHistorialAdmin").addEventListener("change", () => {
            mes = $("mesHistorialAdmin").value;
            pagina = 0;
            cargarResumen(); cargarHistorial();
        });
        $("historialAnteriorAdmin").addEventListener("click", () => { if (pagina > 0) { pagina--; cargarHistorial(); } });
        $("historialSiguienteAdmin").addEventListener("click", () => { if (pagina + 1 < paginas) { pagina++; cargarHistorial(); } });
        $("selectEspacioAgenda").addEventListener("change", cargarAgenda);
        $("inputFechaAgenda").addEventListener("change", () => {
            if (!$("selectEspacioAgenda").disabled) cargarAgenda();
        });
        $("btnActualizarAgendaAdmin").addEventListener("click", cargarEspacios);
        document.addEventListener("visibilitychange", () => {
            if (document.visibilityState === "visible") refrescar();
        });
        const resultado = $("resultadoValidacionReserva");
        if (resultado) new MutationObserver(() => {
            clearTimeout(temporizador);
            temporizador = setTimeout(refrescar, 500);
        }).observe(resultado, { childList: true, characterData: true, subtree: true });
        cargarResumen();
    });
})();
