package com.att.tdp.issueflow.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code app.jwt.*} block in application.yaml.
 * Wired into JwtService via constructor injection.
 *
 *   app.jwt.secret              base64 or raw — at least 32 bytes for HS256
 *   app.jwt.expiration-seconds  token lifetime (default 3600 = 1 hour)
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        long expirationSeconds
) {}