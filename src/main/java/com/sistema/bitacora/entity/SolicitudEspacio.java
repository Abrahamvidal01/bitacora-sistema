package com.sistema.bitacora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "solicitudes_espacio")
@Getter
@Setter
@NoArgsConstructor
public class SolicitudEspacio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String folio;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "espacio_id", nullable = false)
    private Espacio espacio;

    @Column(name = "fecha_evento", nullable = false)
    private LocalDate fechaEvento;

    @Column(name = "hora_inicio", nullable = false)
    private LocalTime horaInicio;

    @Column(name = "hora_fin", nullable = false)
    private LocalTime horaFin;

    @Column(name = "motivo_evento", nullable = false, length = 255)
    private String motivoEvento;

    @Column(name = "recursos_externos", columnDefinition = "TEXT")
    private String recursosExternos;

    @Column(name = "ruta_oficio_requisicion", length = 255)
    private String rutaOficioRequisicion;

    @Column(name = "es_urgente_sin_oficio")
    private Boolean esUrgenteSinOficio = false;

    @Column(name = "estatus_solicitud", length = 30)
    private String estatusSolicitud = "PENDIENTE";

    @Column(name = "tipo_origen", length = 30)
    private String tipoOrigen = "SOLICITUD_DOCENTE";

    @Column(
            name = "fecha_solicitud",
            insertable = false,
            updatable = false
    )
    private LocalDateTime fechaSolicitud;
}
