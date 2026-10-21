document.addEventListener("DOMContentLoaded", () => {
    const formulario = document.getElementById("loginForm");
    const mensaje = document.getElementById("loginMensaje");
    const boton = formulario.querySelector('button[type="submit"]');

    formulario.addEventListener("submit", async (evento) => {
        evento.preventDefault();
        boton.disabled = true;
        mensaje.className = "alert d-none";

        try {
            const respuesta = await fetch("/api/v1/auth/login", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({
                    correo: document.getElementById("loginEmail").value.trim(),
                    password: document.getElementById("loginPassword").value
                })
            });

            if (!respuesta.ok) {
                mensaje.textContent = respuesta.status === 401
                    ? "Correo o contraseña incorrectos, o cuenta desactivada."
                    : "No se pudo iniciar sesión. Inténtalo nuevamente.";
                mensaje.className = "alert alert-danger";
                return;
            }

            const datos = await respuesta.json();

            if (!datos.token || !Array.isArray(datos.roles)) {
                throw new Error("Respuesta inesperada");
            }

            // Primera prueba: todavía no almacenamos ni mostramos el token.
            mensaje.textContent =
                "Inicio de sesión correcto. Roles: " + datos.roles.join(", ");
            mensaje.className = "alert alert-success";
        } catch {
            mensaje.textContent =
                "No se pudo completar el inicio de sesión. Revisa el servidor.";
            mensaje.className = "alert alert-danger";
        } finally {
            document.getElementById("loginPassword").value = "";
            boton.disabled = false;
        }
    });
});