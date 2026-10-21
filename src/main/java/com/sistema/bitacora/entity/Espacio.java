package com.sistema.bitacora.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "espacios")
@Getter
@Setter
@NoArgsConstructor
public class Espacio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre_espacio",
            nullable = false,
            unique = true,
            length = 100)
    private String nombreEspacio;

    @Column(nullable = false, length = 150)
    private String ubicacion;

    @Column(nullable = false)
    private Integer capacidad;

    @Column(name = "estado_actual", length = 30)
    private String estadoActual = "DISPONIBLE";

    @Column(name = "recursos_base_json", columnDefinition = "TEXT")
    private String recursosBaseJson;

    @Column
    private Boolean activo = true;
}
