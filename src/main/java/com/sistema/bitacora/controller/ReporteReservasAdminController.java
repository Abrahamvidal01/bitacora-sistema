package com.sistema.bitacora.controller;
import com.sistema.bitacora.service.GeneradorReportesReservas;
import com.sistema.bitacora.service.HistorialAdminExportacionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;

@RestController
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class ReporteReservasAdminController {
    private final HistorialAdminExportacionService historial;
    private final GeneradorReportesReservas generador;
    public ReporteReservasAdminController(HistorialAdminExportacionService historial, GeneradorReportesReservas generador) {
        this.historial = historial; this.generador = generador;
    }
    @GetMapping("/admin/dashboard/historial/reporte")
    public ResponseEntity<byte[]> descargar(@RequestParam(name = "mes", required = false) String mes,
            @RequestParam(name = "formato") String formato) {
        if (!"pdf".equals(formato) && !"xlsx".equals(formato)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona PDF o Excel");
        var datos = historial.consultar(mes); byte[] bytes;
        try { bytes = "pdf".equals(formato) ? generador.pdf(datos) : generador.excel(datos); }
        catch (IOException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudo generar el reporte completo. Si falla el PDF, prueba Excel", ex);
        }
        String tipo = "pdf".equals(formato) ? "application/pdf" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(tipo))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reservas_urgentes_" + datos.mes() + "." + formato + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private").header("X-Content-Type-Options", "nosniff")
                .contentLength(bytes.length).body(bytes);
    }
}
