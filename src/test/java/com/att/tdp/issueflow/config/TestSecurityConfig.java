package com.att.tdp.issueflow.config;

import com.att.tdp.issueflow.common.error.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Security configuration used by @WebMvcTest slices.
 *
 * Mirrors SecurityConfig's path rules but does NOT install the JWT filter
 * — slice tests inject an authenticated principal directly via
 * .with(user(...)), bypassing the filter chain. Avoiding the real filter
 * keeps the slice context free of JwtService / RevokedTokenRepository
 * dependencies that the slice has no need for.
 *
 * The custom 401/403 handlers are duplicated here so security failures
 * still return the ApiError shape in tests.
 *
 * Imported by controller slice tests via @Import(TestSecurityConfig.class).
 */
@TestConfiguration
@EnableMethodSecurity
public class TestSecurityConfig {

    @Bean
    public SecurityFilterChain testFilterChain(HttpSecurity http,
                                               ObjectMapper json) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/login", "/swagger-ui/**", "/v3/api-docs/**", "/error")
                    .permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(testAuthEntryPoint(json))
                .accessDeniedHandler(testAccessDeniedHandler(json))
            );

        return http.build();
    }

    private AuthenticationEntryPoint testAuthEntryPoint(ObjectMapper json) {
        return (request, response, ex) ->
                writeProblem(response, request, json, HttpStatus.UNAUTHORIZED,
                             "Authentication required");
    }

    private AccessDeniedHandler testAccessDeniedHandler(ObjectMapper json) {
        return (request, response, ex) ->
                writeProblem(response, request, json, HttpStatus.FORBIDDEN,
                             "Access denied");
    }

    private static void writeProblem(HttpServletResponse response,
                                     HttpServletRequest request,
                                     ObjectMapper json,
                                     HttpStatus status,
                                     String message) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(status, message, request.getRequestURI());
        response.getWriter().write(json.writeValueAsString(body));
    }
}