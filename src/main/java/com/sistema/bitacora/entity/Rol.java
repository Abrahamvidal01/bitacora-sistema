package com.sistema.bitacora.entity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class Rol {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre_rol", nullable = false, unique = true, length = 50)
    private String nombreRol; // ADMINISTRADOR, SUPERVISOR, SOLICITANTE, TECNICO_SOPORTE

    @Column(length = 255)
    private String descripcion;

}
