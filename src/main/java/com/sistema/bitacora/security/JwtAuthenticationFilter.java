package com.sistema.bitacora.security;

import com.sistema.bitacora.entity.Usuario;
import com.sistema.bitacora.repository.UsuarioRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UsuarioRepository usuarioRepository;

    public JwtAuthenticationFilter(
            JwtUtils jwtUtils,
            UsuarioRepository usuarioRepository) {
        this.jwtUtils = jwtUtils;
        this.usuarioRepository = usuarioRepository;
    }

    @Override
    @NullMarked
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (jwtUtils.validateToken(token)) {
                String correo = jwtUtils.getCorreoFromToken(token);

                Optional<Usuario> usuarioOpt =
                        usuarioRepository.findByCorreoInstitucional(correo);

                if (usuarioOpt.isEmpty()
                        || !Boolean.TRUE.equals(usuarioOpt.get().getActivo())) {
                    SecurityContextHolder.clearContext();
                    response.sendError(
                            HttpServletResponse.SC_UNAUTHORIZED,
                            "Usuario no disponible"
                    );
                    return;
                }

                Usuario usuario = usuarioOpt.get();

                List<SimpleGrantedAuthority> authorities =
                        usuario.getRoles().stream()
                                .map(rol -> new SimpleGrantedAuthority(
                                        "ROLE_" + rol.getNombreRol()))
                                .toList();

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                usuario.getCorreoInstitucional(),
                                null,
                                authorities
                        );

                SecurityContextHolder.getContext()
                        .setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }
}
