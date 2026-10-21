document.addEventListener("DOMContentLoaded", () => {
    const modal = document.getElementById("modalEditarEspacio");

    if (!modal) return;

    modal.addEventListener("show.bs.modal", (evento) => {
        const boton = evento.relatedTarget;

        if (!boton) return;

        modal.querySelector("#editarId").value =
            boton.dataset.id;

        modal.querySelector("#editarNombre").value =
            boton.dataset.nombre;

        modal.querySelector("#editarUbicacion").value =
            boton.dataset.ubicacion;

        modal.querySelector("#editarCapacidad").value =
            boton.dataset.capacidad;

        modal.querySelector("#editarEstado").value =
            boton.dataset.estado;
    });
});