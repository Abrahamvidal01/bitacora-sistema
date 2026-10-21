package com.sistema.bitacora.controller;

import com.sistema.bitacora.entity.Rol;
import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.repository.UsuarioRepository;
import com.sistema.bitacora.security.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/auth")

public class AuthController {

    @Autowired
    private PasswordEncoder passwordEncoder;


    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        String correo = request.get("correo");
        String password = request.get("password");


        if (correo == null || correo.isBlank()
                || password == null || password.isBlank()) {
            return ResponseEntity.badRequest()
                    .body("Correo y contraseña son obligatorios");
        }

        Optional<Usuario> usuarioOpt = usuarioRepository.findByCorreoInstitucional(correo);

        if (usuarioOpt.isPresent()) {       // este comprueba que existe el usario...
            Usuario usuario = usuarioOpt.get();

            // Verifica que el usuario esté activo y la contraseña coincida con BCrypt
            if (Boolean.TRUE.equals(usuario.getActivo())
                    && passwordEncoder.matches(password, usuario.getPasswordHash())) {

                List<String> roles = usuario.getRoles().stream()
                        .map(Rol::getNombreRol)
                        .collect(Collectors.toList());

                String token = jwtUtils.generateToken(usuario.getCorreoInstitucional(), roles);

                Map<String, Object> response = new HashMap<>();
                response.put("token", token);
                response.put("correo", usuario.getCorreoInstitucional());
                response.put("nombre", usuario.getNombreCompleto());
                response.put("roles", roles);

                return ResponseEntity.ok(response);
            }
        }

        return ResponseEntity.status(401).body("Credenciales incorrectas");
    }

}
