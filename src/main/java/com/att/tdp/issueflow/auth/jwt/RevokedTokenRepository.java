package com.att.tdp.issueflow.auth.jwt;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String> {

    boolean existsByJti(String jti);

    /**
     * Purges entries whose expires_at is in the past. Called by a daily
     * scheduled job (Phase 13 setup). Keeps the deny-list small over time.
     */
    @Modifying
    @Query("DELETE FROM RevokedToken r WHERE r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    /**
     * Deletes revoked-token entries whose underlying JWT has already expired.
     * Once the JWT itself is expired, the deny-list entry is no longer needed —
     * the JWT filter would reject the token on expiry alone.
     *
     * Bulk delete via @Modifying + JPQL; clearAutomatically=true keeps the
     * Hibernate first-level cache consistent if the same EntityManager is
     * reused.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM RevokedToken r WHERE r.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}