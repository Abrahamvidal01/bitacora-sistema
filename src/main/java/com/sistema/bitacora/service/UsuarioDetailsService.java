package com.sistema.bitacora.service;
// Aquí se utiliza el hash ya guardado, sin generar otro. El constructor de Spring admite varios roles y permite marcar la cuenta como deshabilitada.
import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.repository.UsuarioRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import org.jspecify.annotations.NullMarked;

@Service
public class UsuarioDetailsService implements UserDetailsService {

    private final UsuarioRepository usuarioRepository;

    public UsuarioDetailsService(UsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    @Override
    @NullMarked
    public UserDetails loadUserByUsername(String correo)
            throws UsernameNotFoundException {

        Usuario usuario = usuarioRepository
                .findByCorreoInstitucional(correo)
                .orElseThrow(() ->
                        new UsernameNotFoundException(
                                "Credenciales incorrectas"
                        ));

        String[] roles = usuario.getRoles().stream()
                .map(rol -> rol.getNombreRol())
                .toArray(String[]::new);

        return User.withUsername(usuario.getCorreoInstitucional())
                .password(usuario.getPasswordHash())
                .roles(roles)
                .disabled(!Boolean.TRUE.equals(usuario.getActivo()))
                .build();
    }
}