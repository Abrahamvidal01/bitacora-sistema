package com.sistema.bitacora.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.List;
@Component
public class JwtUtils {

    // Clave secreta para firmar los tokens (mínimo 256 bits / 32 caracteres)
    @Value("${jwt.secret}")
    private String secretKey;

    // 8 horas = 28,800,000 ms
    @Value("${jwt.expiration:28800000}")
    private long jwtExpiration;

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    // Genera el token e incluye la lista de roles (ADMINISTRADOR, SUPERVISOR, SOLICITANTE)
    public String generateToken(String correo, List<String> roles) {
        return Jwts.builder()
                .setSubject(correo)
                .claim("roles", roles)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    // Extrae el correo del usuario (Subject) desde el token
    public String getCorreoFromToken(String token) {
        return getClaims(token).getSubject();
    }

    // Extrae la lista de roles almacenada dentro del token
    @SuppressWarnings("unchecked")
    public List<String> getRolesFromToken(String token) {
        return getClaims(token).get("roles", List.class);
    }

    // Valida la firma y expiración del token
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
