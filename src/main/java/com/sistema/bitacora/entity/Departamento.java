package com.sistema.bitacora.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "departamentos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class Departamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre_departamento", nullable = false, unique = true, length = 100)
    private String nombreDepartamento;

    @Column(name = "clave_departamento", length = 20)
    private String claveDepartamento;

    @Column(nullable = false)
    private Boolean activo = true;

}
