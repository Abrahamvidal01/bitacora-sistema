// Independiente del formulario y del JavaScript de reservas.
document.addEventListener("DOMContentLoaded", () => {
    const modal = document.getElementById("modalEliminarEspacio");
    if (!modal) return;
    const formulario = document.getElementById("formEliminarEspacio");
    const id = document.getElementById("eliminarEspacioId");
    const nombre = document.getElementById("eliminarEspacioNombre");
    const confirmar = document.getElementById("confirmarEliminarEspacio");
    const enviar = document.getElementById("btnEliminarEspacioDefinitivo");
    let enviando = false;
    const valido = () => /^[1-9][0-9]{0,18}$/.test(id.value) && confirmar.checked;
    const actualizar = () => { enviar.disabled = enviando || !valido(); };
    modal.addEventListener("show.bs.modal", evento => {
        formulario.reset();
        enviando = false;
        const boton = evento.relatedTarget;
        id.value = boton?.dataset.id || "";
        nombre.textContent = boton?.dataset.nombre || "Espacio no seleccionado";
        actualizar();
    });
    confirmar.addEventListener("change", actualizar);
    formulario.addEventListener("submit", evento => {
        if (enviando || !valido()) {
            evento.preventDefault();
            return;
        }
        enviando = true;
        actualizar();
    });
    window.addEventListener("pageshow", () => { enviando = false; actualizar(); });
});
