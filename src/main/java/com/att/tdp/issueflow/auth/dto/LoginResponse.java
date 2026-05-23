package com.att.tdp.issueflow.auth.dto;

/**
 * Response body for POST /auth/login. Matches the README contract:
 * { accessToken, tokenType: "Bearer", expiresIn: 3600 }
 *
 * tokenType is hardcoded to "Bearer" because the API only issues Bearer
 * tokens. expiresIn is in seconds, conventional for JWT responses.
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
    public static LoginResponse bearer(String token, long expiresInSeconds) {
        return new LoginResponse(token, "Bearer", expiresInSeconds);
    }
}