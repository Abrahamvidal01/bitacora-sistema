package com.sistema.bitacora.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ReservaTiempoConfig {

    @Bean("relojReservas")
    public Clock relojReservas() {
        return Clock.system(ZoneId.of("America/Mexico_City"));
    }
}
