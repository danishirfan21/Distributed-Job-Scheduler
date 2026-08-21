package com.distributed.jobscheduler.scheduler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * No identity provider (e.g. Keycloak) is bundled with this project's docker-compose stack,
 * so OAuth2/JWT enforcement is intentionally not wired up here - it would make the REST API
 * unusable out of the box. All endpoints are open. See README "Known Limitations" before
 * using this as a starting point for a real deployment: put this service behind a real
 * OAuth2 resource server config (issuer-uri/jwk-set-uri) and re-enable authentication on
 * /api/** before exposing it outside a local machine.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }
}
