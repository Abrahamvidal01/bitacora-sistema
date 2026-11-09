package com.sistema.bitacora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "solicitud_recursos",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_solicitud_recurso_inventario",
                columnNames = {"solicitud_id", "inventario_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class SolicitudRecurso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(lombok.AccessLevel.NONE)
    private Long id;

    @Column(
            name = "solicitud_id",
            nullable = false,
            updatable = false
    )
    private Long solicitudId;

    @Column(
            name = "inventario_id",
            nullable = false,
            updatable = false
    )
    private Long inventarioId;

    @Column(
            name = "espacio_id",
            nullable = false,
            updatable = false
    )
    private Long espacioId;

    @Column(
            name = "nombre_item",
            nullable = false,
            length = 150,
            updatable = false
    )
    private String nombreItem;

    @Column(
            name = "descripcion_item",
            columnDefinition = "TEXT",
            updatable = false
    )
    private String descripcionItem;

    @Column(
            name = "estado_bien",
            nullable = false,
            length = 50,
            updatable = false
    )
    private String estadoBien;

    @Column(
            name = "cantidad_solicitada",
            nullable = false,
            updatable = false
    )
    private Integer cantidadSolicitada;

    @Column(
            name = "existencias_al_confirmar",
            nullable = false,
            updatable = false
    )
    private Integer existenciasAlConfirmar;

    @Column(
            name = "fecha_registro",
            nullable = false,
            insertable = false,
            updatable = false
    )
    @Setter(lombok.AccessLevel.NONE)
    private LocalDateTime fechaRegistro;
}