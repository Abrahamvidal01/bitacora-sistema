package com.sistema.bitacora.service;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;
import java.util.List;

@Service
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class HistorialAdminExportacionService {
    private static final int LIMITE = 2000;
    private final DashboardAdminService dashboard;
    public HistorialAdminExportacionService(DashboardAdminService dashboard) { this.dashboard = dashboard; }
    // Mismo filtro del historial. Invocar desde el controlador mediante el bean Spring.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public Datos consultar(String mes) {
        var primera = dashboard.historial(mes, 0);
        if (primera.total() > LIMITE) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El mes supera 2000 reservas. No se genero un reporte parcial");
        if (primera.total() < 0 || primera.pagina() != 0
                || primera.totalPaginas() != Math.max(1L, (primera.total() + 19L) / 20L)) {
            throw new IllegalStateException("Paginacion de historial inconsistente");
        }
        List<DashboardAdminService.ReservaHistorial> filas = new ArrayList<>(primera.reservas());
        for (int pagina = 1; pagina < primera.totalPaginas(); pagina++) {
            var siguiente = dashboard.historial(primera.mes(), pagina);
            if (siguiente.pagina() != pagina || siguiente.total() != primera.total()
                    || siguiente.totalPaginas() != primera.totalPaginas()
                    || !primera.mes().equals(siguiente.mes())) throw new IllegalStateException("Historial inconsistente");
            filas.addAll(siguiente.reservas());
            if (filas.size() > LIMITE) throw new IllegalStateException("Historial excedido");
        }
        if (filas.size() != primera.total()) throw new IllegalStateException("Historial incompleto");
        return new Datos(primera.mes(), filas);
    }
    public record Datos(String mes, List<DashboardAdminService.ReservaHistorial> filas) {
        public Datos { filas = List.copyOf(filas); }
    }
}
