package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.auth.jwt.JwtProperties;
import com.att.tdp.issueflow.auth.jwt.JwtService;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for JwtService. No Spring context — JwtService is constructed
 * directly with handcrafted properties. Verifies issuance, claim shape,
 * signature verification, and rejection of tampered or expired tokens.
 */
class JwtServiceTest {

    private static final String SECRET =
            "test-secret-must-be-at-least-32-bytes-long-xxxxxxxxxxxxxx";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(new JwtProperties(SECRET, 3600));
    }

    private User testUser() {
        User u = new User("alice", "alice@x.com", "hash", "Alice", UserRole.DEVELOPER);
        // Test-only id injection because BaseEntity.id is set by Hibernate.
        ReflectionTestUtils.setField(u, "id", 42L);
        return u;
    }

    @Test
    void rejectsSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties("too-short", 3600)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void issuedToken_carriesExpectedClaims() {
        JwtService.IssuedToken issued = jwtService.issueFor(testUser());

        Claims claims = jwtService.parse(issued.token());
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.get("uid", Long.class)).isEqualTo(42L);
        assertThat(claims.get("role", String.class)).isEqualTo("DEVELOPER");
        assertThat(claims.getId()).isEqualTo(issued.jti());
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void parse_rejectsTamperedToken() {
        String token = jwtService.issueFor(testUser()).token();

        // Flip a character in the payload section (the middle part of the JWT).
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = payload[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        assertThatThrownBy(() -> jwtService.parse(tampered))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void parse_rejectsTokenSignedWithDifferentSecret() {
        JwtService otherService = new JwtService(new JwtProperties(
                "different-secret-also-at-least-32-bytes-long-yyyyyyyyyy", 3600));
        String tokenFromOther = otherService.issueFor(testUser()).token();

        assertThatThrownBy(() -> jwtService.parse(tokenFromOther))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void parse_rejectsExpiredToken() {
        // Negative expiration -> issued already expired.
        JwtService expiredService = new JwtService(new JwtProperties(SECRET, -10));
        String token = expiredService.issueFor(testUser()).token();

        assertThatThrownBy(() -> jwtService.parse(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void extractJti_recoversJtiEvenFromExpiredToken() {
        JwtService expiredService = new JwtService(new JwtProperties(SECRET, -10));
        JwtService.IssuedToken issued = expiredService.issueFor(testUser());

        String recovered = expiredService.extractJti(issued.token());
        assertThat(recovered).isEqualTo(issued.jti());
    }

    @Test
    void jtiIsUniquePerIssuance() {
        String jti1 = jwtService.issueFor(testUser()).jti();
        String jti2 = jwtService.issueFor(testUser()).jti();
        assertThat(jti1).isNotEqualTo(jti2);
    }
}