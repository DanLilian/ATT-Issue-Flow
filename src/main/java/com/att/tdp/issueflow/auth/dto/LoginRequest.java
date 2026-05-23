package com.att.tdp.issueflow.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /auth/login. Only structural validation here —
 * credential verification happens in AuthService via AuthenticationManager.
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
) {}