package com.sistema.bitacora.config;

import com.sistema.bitacora.security.JwtAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.sistema.bitacora.security.ValidacionSesionFilter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    // API: conserva autenticación JWT, sin utilizar sesiones.
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(
                        jwtAuthFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    // Páginas web: sesión y protección CSRF habilitada.
    @Bean
    @Order(2)
    public SecurityFilterChain webSecurity(
            HttpSecurity http,
            UserDetailsService userDetailsService
    ) throws Exception {
        http
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/", "/login", "/error",
                                "/css/**", "/js/**", "/images/**",
                                "/favicon.ico"
                        ).permitAll()
                        .requestMatchers("/admin/**")
                        .hasRole("ADMINISTRADOR")
                        .requestMatchers("/supervisor/**")
                        .hasAnyRole("ADMINISTRADOR", "SUPERVISOR")
                        .requestMatchers("/solicitante/**")
                        .hasRole("SOLICITANTE")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/web/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/bitacoras", true)
                        .failureUrl("/login?error")
                        .permitAll()
                )

                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")

                );

        http.addFilterBefore(
                new ValidacionSesionFilter(userDetailsService),
                AuthorizationFilter.class
        );

        return http.build();
    }

    // Evita que el filtro JWT se ejecute también fuera de la API.
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter>
    jwtFilterRegistration(JwtAuthenticationFilter filter) {

        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);

        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}