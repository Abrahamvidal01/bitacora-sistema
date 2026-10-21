package com.sistema.bitacora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "inventario_espacios")
@Getter
@Setter
@NoArgsConstructor
public class InventarioEspacio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre_item", nullable = false, length = 150)
    private String nombreItem;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Column(nullable = false)
    private Integer cantidad = 1;

    @Column(name = "estado_bien", length = 50)
    private String estadoBien = "BUENO";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "espacio_id", nullable = false)
    private Espacio espacio;

    @Column(
            name = "fecha_registro",
            insertable = false,
            updatable = false
    )
    private LocalDateTime fechaRegistro;
}
