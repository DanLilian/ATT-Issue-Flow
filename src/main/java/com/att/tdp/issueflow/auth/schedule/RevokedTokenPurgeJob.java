package com.att.tdp.issueflow.auth.schedule;

import com.att.tdp.issueflow.auth.jwt.RevokedTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Periodic cleanup of the revoked-tokens deny-list.
 *
 * Once a JWT's natural expiry has passed, the deny-list entry serves no
 * purpose — the JwtAuthenticationFilter would reject the token on expiry
 * regardless. Without this cleanup the table grows unbounded.
 *
 * Schedule is configured via the property
 * {@code issueflow.schedule.revoked-token-purge-cron} (default: daily
 * at 03:00 server local time). Set to "-" to disable.
 *
 * Not audited: the cleanup has no user-visible effect, and audit is
 * reserved for state changes that matter to users.
 */
@Component
public class RevokedTokenPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RevokedTokenPurgeJob.class);

    private final RevokedTokenRepository revokedTokenRepository;

    public RevokedTokenPurgeJob(RevokedTokenRepository revokedTokenRepository) {
        this.revokedTokenRepository = revokedTokenRepository;
    }

    @Scheduled(cron = "${issueflow.schedule.revoked-token-purge-cron}")
    @Transactional
    public void run() {
        try {
            int removed = revokedTokenRepository.deleteByExpiresAtBefore(Instant.now());
            if (removed > 0) {
                log.info("Purged {} expired revoked-token entries", removed);
            } else {
                log.debug("Revoked-token purge complete: nothing to remove");
            }
        } catch (Exception ex) {
            log.error("Revoked-token purge failed", ex);
        }
    }
}