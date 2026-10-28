document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll(
        "select[id*='Espacio'], select[id*='espacio']"
    ).forEach(select => {

        // Este modal carga inventario real con reserva-inventario.js.
        if (select.closest("#modalNuevaReserva")) {
            return;
        }

        select.addEventListener("change", (e) => {
            const modal = e.target.closest(".modal-body");
            if (!modal) return;

            const valorEspacio = e.target.value;
            const msgSinEspacio = modal.querySelector("#msgSinEspacio");
            const gridInventario = modal.querySelector("#gridInventario");
            const items = modal.querySelectorAll(".item-inventario");

            if (!valorEspacio || valorEspacio === "todos") {
                if (msgSinEspacio) {
                    msgSinEspacio.classList.remove("d-none");
                }

                if (gridInventario) {
                    gridInventario.classList.add("d-none");
                }
            } else {
                if (msgSinEspacio) {
                    msgSinEspacio.classList.add("d-none");
                }

                if (gridInventario) {
                    gridInventario.classList.remove("d-none");
                }

                items.forEach(item => {
                    const espaciosPermitidos = (
                        item.getAttribute("data-espacios") || ""
                    ).split(",");

                    if (espaciosPermitidos.includes(valorEspacio)) {
                        item.classList.remove("d-none");
                    } else {
                        item.classList.add("d-none");

                        const chk = item.querySelector(
                            "input[type='checkbox']"
                        );

                        if (chk) chk.checked = false;
                    }
                });
            }
        });
    });
});