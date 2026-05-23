package com.att.tdp.issueflow.auth.jwt;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Entry in the JWT deny-list. A token is considered revoked iff its jti
 * appears in this table. The expires_at column lets a scheduled job
 * purge entries that have aged past their original token's expiry —
 * once a token is expired by signature, the deny-list entry is redundant.
 *
 * Not extending BaseEntity: this table has no business identity or
 * update lifecycle. The jti IS the primary key.
 */
@Entity
@Table(name = "revoked_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RevokedToken {

    @Id
    @Column(length = 64)
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;

    public RevokedToken(String jti, Instant expiresAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
        this.revokedAt = Instant.now();
    }
}