package com.distributed.jobscheduler.scheduler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Allow actuator endpoints
                .requestMatchers("/actuator/**").permitAll()
                // Allow health check
                .requestMatchers("/health").permitAll()
                // Require authentication for all API endpoints
                .requestMatchers("/api/**").authenticated()
                // Allow everything else (can be restricted further)
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(
                    new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter()
                ))
            );

        return http.build();
    }
}
