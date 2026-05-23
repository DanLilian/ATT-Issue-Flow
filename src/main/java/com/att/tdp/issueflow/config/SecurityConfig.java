package com.att.tdp.issueflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Placeholder security configuration for Phase 5a.
 *
 * Permits all endpoints so existing tests keep passing while we build
 * the JWT primitives. Sub-commit 5b replaces this with the real config:
 * JWT filter, custom 401/403 handlers, @PreAuthorize enabled, and
 * everything locked down except /auth/login and Swagger.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}