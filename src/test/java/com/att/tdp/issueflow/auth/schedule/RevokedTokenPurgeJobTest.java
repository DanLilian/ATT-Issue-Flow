package com.att.tdp.issueflow.auth.schedule;

import com.att.tdp.issueflow.auth.jwt.RevokedToken;
import com.att.tdp.issueflow.auth.jwt.RevokedTokenRepository;
import com.att.tdp.issueflow.common.JpaConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test for the deny-list purge job. Invokes the job's run() method
 * directly rather than waiting for the scheduler (which is disabled in
 * tests via the "-" cron sentinel).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RevokedTokenPurgeJob.class, JpaConfig.class})
class RevokedTokenPurgeJobTest {

    @Autowired RevokedTokenPurgeJob job;
    @Autowired RevokedTokenRepository repository;
    @PersistenceContext EntityManager entityManager;

    @BeforeEach
    void clearRepository() {
        repository.deleteAll();
        entityManager.flush();
    }

    @Test
    void purge_removesExpiredEntries_keepsActiveOnes() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

        repository.save(new RevokedToken("expired-jti-1", past));
        repository.save(new RevokedToken("expired-jti-2", past));
        repository.save(new RevokedToken("active-jti", future));
        entityManager.flush();

        job.run();
        entityManager.flush();
        entityManager.clear();

        // Only the active one remains.
        assertThat(repository.findAll()).hasSize(1)
                .extracting(RevokedToken::getJti)
                .containsExactly("active-jti");
    }

    @Test
    void purge_isIdempotent_onEmptyTable() {
        // No exception, no error.
        job.run();
        entityManager.flush();

        assertThat(repository.count()).isEqualTo(0);
    }

    @Test
    void purge_isNoOp_whenNothingExpired() {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        repository.save(new RevokedToken("active-1", future));
        repository.save(new RevokedToken("active-2", future));
        entityManager.flush();

        job.run();
        entityManager.flush();

        assertThat(repository.count()).isEqualTo(2);
    }
}