document.addEventListener("DOMContentLoaded", () => {
    const modal = document.getElementById("modalNuevaReserva");

    if (!modal) return;

    const campo = modal.querySelector(
        "#reservaDirectaRecursosExternos"
    );

    const aviso = modal.querySelector(
        "#avisoRecursosExternos"
    );

    if (!campo || !aviso) return;

    function actualizarAviso() {
        const tieneRecursosExternos =
            campo.value.trim().length > 0;

        aviso.hidden = !tieneRecursosExternos;
    }

    campo.addEventListener("input", actualizarAviso);

    actualizarAviso();
});