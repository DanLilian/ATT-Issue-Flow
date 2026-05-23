package com.att.tdp.issueflow.auth.jwt;

import com.att.tdp.issueflow.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies JWTs. Symmetric HS256 signing — the same service
 * issues and verifies, so an asymmetric key pair offers no benefit.
 *
 * Token claims:
 *   sub  username
 *   uid  user id (numeric; saves a DB lookup on every authenticated call)
 *   role user role (ADMIN | DEVELOPER)
 *   jti  unique token id, used as the deny-list key on logout
 *   iat  issued at
 *   exp  expiry (iat + app.jwt.expiration-seconds)
 *
 * Verification failures throw {@link JwtException}; callers (the auth
 * filter) catch and treat as unauthenticated.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationSeconds;

    public JwtService(JwtProperties props) {
        // jjwt requires HS256 keys >= 256 bits = 32 bytes. We validate at
        // startup so a too-short secret fails loud, not at first signature.
        byte[] keyBytes = props.secret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                "app.jwt.secret must be at least 32 bytes for HS256 (got " + keyBytes.length + ")");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationSeconds = props.expirationSeconds();
    }

    /** Issued token for the given user. Includes a fresh jti for revocation. */
    public IssuedToken issueFor(User user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expirationSeconds);
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .subject(user.getUsername())
                .claim("uid",  user.getId())
                .claim("role", user.getRole().name())
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, jti, exp, expirationSeconds);
    }

    /**
     * Parses and validates the signature + expiry. Returns the verified
     * claims. Throws {@link JwtException} (or subclass) on any failure:
     * malformed, bad signature, expired.
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Convenience: extract the jti without re-throwing on expired tokens. */
    public String extractJti(String token) {
        try {
            return parse(token).getId();
        } catch (ExpiredJwtException ex) {
            // Even an expired token has a readable jti in its claims; we may
            // want to revoke it pre-emptively or just ignore. Returning the
            // jti lets the caller decide.
            return ex.getClaims().getId();
        }
    }

    public record IssuedToken(String token, String jti, Instant expiresAt, long expiresInSeconds) {}
}