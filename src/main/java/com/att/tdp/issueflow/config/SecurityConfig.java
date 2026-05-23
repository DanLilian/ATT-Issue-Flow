package com.att.tdp.issueflow.config;

import com.att.tdp.issueflow.auth.jwt.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Application security configuration.
 *
 * Authentication model: stateless JWT in Authorization: Bearer header,
 * with a small DB-backed deny-list for logout (see JwtAuthenticationFilter
 * and RevokedTokenRepository).
 *
 * Public endpoints: POST /auth/login, Swagger, error.
 * All other endpoints require authentication. Role-based authorization
 * is applied per-method via @PreAuthorize (enabled by @EnableMethodSecurity)
 * — keeps the authorization rule next to the controller method it gates.
 *
 * On failure:
 *   401 -> authenticationEntryPoint() returns ApiError JSON
 *   403 -> accessDeniedHandler() returns ApiError JSON
 */
@Configuration
@EnableMethodSecurity   // turns on @PreAuthorize / @PostAuthorize
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler) throws Exception {

        http
            // CSRF defends cookie/session auth from forged form posts.
            // JWT in Authorization header is immune by construction.
            .csrf(csrf -> csrf.disable())

            // No HTTP session — every request is independently authenticated
            // by its Bearer token.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Disable Spring Security's defaults that don't fit our model.
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())

            // Path-based authorization rules. Specific rules first; the
            // catch-all anyRequest().authenticated() applies to everything
            // else (including future endpoints we haven't written yet).
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated()
            )

            // Our custom 401 / 403 problem handlers.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )

            // JWT filter populates the security context before the
            // default authentication filter would have a chance to.
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Exposes the AuthenticationManager so AuthService (Phase 5c) can
     * delegate password verification to Spring's DaoAuthenticationProvider.
     * Spring Boot auto-configures the provider with our PasswordEncoder
     * and AppUserDetailsService.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg)
            throws Exception {
        return cfg.getAuthenticationManager();
    }
}