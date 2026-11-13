document.addEventListener("DOMContentLoaded", () => {
    const caja = document.getElementById("descargasHistorialAdmin");
    const mes = document.getElementById("mesHistorialAdmin");
    const estado = document.getElementById("estadoDescargaHistorialAdmin");
    if (!caja || !mes || !estado) return;
    const botones = [...caja.querySelectorAll("button[data-formato]")]; let ocupado = false;
    botones.forEach(boton => boton.addEventListener("click", async () => {
        if (ocupado) return;
        const valorMes = mes.value; const formato = boton.dataset.formato;
        if (!/^\d{4}-\d{2}$/.test(valorMes) || !mes.checkValidity()) { estado.textContent = "Selecciona un mes valido antes de descargar."; return; }
        ocupado = true; botones.forEach(b => { b.disabled = true; }); const deshabilitado = mes.disabled; mes.disabled = true;
        estado.textContent = "Generando el reporte completo de " + valorMes + "...";
        const control = new AbortController(); const temporizador = setTimeout(() => control.abort(), 60000);
        let enlace, urlArchivo;
        try {
            const url = new URL(caja.dataset.url, window.location.href);
            url.searchParams.set("mes", valorMes); url.searchParams.set("formato", formato);
            const respuesta = await fetch(url, { credentials: "same-origin", cache: "no-store", redirect: "error", signal: control.signal });
            if (!respuesta.ok) throw new Error("HTTP " + respuesta.status);
            const esperado = formato === "pdf" ? "application/pdf" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            if (!(respuesta.headers.get("Content-Type") || "").toLowerCase().startsWith(esperado)) throw new Error("tipo de archivo inesperado");
            const archivo = await respuesta.blob(); if (!archivo.size) throw new Error("archivo vacio");
            urlArchivo = URL.createObjectURL(archivo); enlace = document.createElement("a");
            enlace.href = urlArchivo; enlace.download = "reservas_urgentes_" + valorMes + "." + formato;
            document.body.appendChild(enlace); enlace.click();
            estado.textContent = "Descarga solicitada: " + valorMes + ". Incluye todas las paginas del historial de ese mes.";
        } catch (error) {
            estado.textContent = "No se pudo descargar el reporte (" + (error.name === "AbortError" ? "tiempo agotado" : error.message)
                + "). Verifica tu sesion. Si falla PDF, prueba Excel. No se entrega un reporte parcial; el limite es 2000 reservas por mes.";
        } finally {
            clearTimeout(temporizador); enlace?.remove(); if (urlArchivo) setTimeout(() => URL.revokeObjectURL(urlArchivo), 60000);
            ocupado = false; mes.disabled = deshabilitado; botones.forEach(b => { b.disabled = false; });
        }
    }));
});
