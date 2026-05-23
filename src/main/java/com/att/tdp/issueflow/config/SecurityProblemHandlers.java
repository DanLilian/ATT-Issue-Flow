package com.att.tdp.issueflow.config;

import com.att.tdp.issueflow.common.error.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Custom {@link AuthenticationEntryPoint} (401) and {@link AccessDeniedHandler}
 * (403) so security failures return the same {@link ApiError} shape as the
 * rest of the API.
 *
 * Spring Security defaults: 401 with an empty body, 403 with the framework's
 * default JSON. Neither matches our error contract. Wiring these in
 * {@link SecurityConfig} is what makes auth errors look like every other
 * error response.
 */
@Configuration
public class SecurityProblemHandlers {

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper json) {
        return (request, response, ex) ->
                writeError(response, request, json, HttpStatus.UNAUTHORIZED,
                           "Authentication required");
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler(ObjectMapper json) {
        return (request, response, ex) ->
                writeError(response, request, json, HttpStatus.FORBIDDEN,
                           "Access denied");
    }

    private static void writeError(HttpServletResponse response,
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