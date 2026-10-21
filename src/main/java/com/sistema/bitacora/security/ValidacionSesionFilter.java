package com.sistema.bitacora.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class ValidacionSesionFilter extends OncePerRequestFilter {

    private final UserDetailsService userDetailsService;

    private final HttpSessionSecurityContextRepository repository =
            new HttpSessionSecurityContextRepository();

    public ValidacionSesionFilter(
            UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        Authentication actual =
                SecurityContextHolder.getContext().getAuthentication();

        if (actual == null
                || !actual.isAuthenticated()
                || actual instanceof AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }

        UserDetails usuario;

        try {
            usuario = userDetailsService.loadUserByUsername(
                    actual.getName()
            );
        } catch (UsernameNotFoundException e) {
            cerrarSesion(request, response);
            return;
        }

        if (!usuario.isEnabled()
                || !usuario.isAccountNonLocked()
                || !usuario.isAccountNonExpired()
                || !usuario.isCredentialsNonExpired()) {
            cerrarSesion(request, response);
            return;
        }

        // Sustituye los permisos de sesión por los actuales de la base.
        UsernamePasswordAuthenticationToken actualizada =
                new UsernamePasswordAuthenticationToken(
                        usuario,
                        null,
                        usuario.getAuthorities()
                );

        actualizada.setDetails(actual.getDetails());

        SecurityContext contexto =
                SecurityContextHolder.createEmptyContext();

        contexto.setAuthentication(actualizada);
        SecurityContextHolder.setContext(contexto);

        repository.saveContext(contexto, request, response);

        filterChain.doFilter(request, response);
    }

    private void cerrarSesion(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        SecurityContextHolder.clearContext();

        HttpSession sesion = request.getSession(false);

        if (sesion != null) {
            sesion.invalidate();
        }

        response.sendRedirect(
                request.getContextPath() + "/login?cuentaNoDisponible"
        );
    }
}
