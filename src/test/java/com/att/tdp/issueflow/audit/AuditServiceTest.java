package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.audit.dto.AuditLogFilter;
import com.att.tdp.issueflow.audit.dto.AuditLogResponse;
import com.att.tdp.issueflow.common.JpaConfig;
import com.att.tdp.issueflow.common.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test for AuditService. We focus on what's specific here:
 *   - records persist with the right fields
 *   - metadata serializes correctly
 *   - filters compose with AND semantics
 *   - newest-first ordering
 *
 * REQUIRES_NEW transaction semantics are not testable inside a
 * @DataJpaTest in isolation (no failing-business-op to roll back here);
 * we verify the *behavior* (audit row exists) and document the
 * transactional intent in the production code.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditService.class, JpaConfig.class, ObjectMapper.class})
class AuditServiceTest {

    @Autowired AuditService auditService;
    @Autowired AuditLogRepository repository;
    @PersistenceContext EntityManager entityManager;

    @BeforeEach
    void clearAuditTable() {
        // AuditService.record uses REQUIRES_NEW, so audit rows commit
        // independently of the test transaction. Without an explicit clear,
        // rows accumulate across tests in this class. We delete in a fresh
        // transaction so the cleanup itself escapes the outer rollback too.
        repository.deleteAll();
        entityManager.flush();
}

    @Test
    void record_persistsAuditEntry_withMetadata() {
        auditService.record(
                AuditAction.TICKET_CREATE, AuditEntityType.TICKET, 1L,
                AuditActor.USER, 42L, Map.of("title", "Bug X"));
        entityManager.flush();
        entityManager.clear();

        var all = repository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getAction()).isEqualTo(AuditAction.TICKET_CREATE);
        assertThat(all.get(0).getEntityType()).isEqualTo(AuditEntityType.TICKET);
        assertThat(all.get(0).getEntityId()).isEqualTo(1L);
        assertThat(all.get(0).getActor()).isEqualTo(AuditActor.USER);
        assertThat(all.get(0).getPerformedBy()).isEqualTo(42L);
        assertThat(all.get(0).getMetadataJson()).contains("\"title\":\"Bug X\"");
    }

    @Test
    void record_persistsAuditEntry_withNullMetadata() {
        auditService.record(
                AuditAction.LOGIN, AuditEntityType.USER, 7L,
                AuditActor.USER, 7L, null);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findAll().get(0).getMetadataJson()).isNull();
    }

    @Test
    void find_returnsEmpty_whenNoEntries() {
        PageResponse<AuditLogResponse> result =
                auditService.find(new AuditLogFilter(null, null, null, null), 1, 10);

        assertThat(result.total()).isZero();
        assertThat(result.data()).isEmpty();
        assertThat(result.page()).isEqualTo(1);
    }

    @Test
    void find_filtersByEntityType() {
        auditService.record(AuditAction.TICKET_CREATE, AuditEntityType.TICKET, 1L,
                AuditActor.USER, 1L, null);
        auditService.record(AuditAction.PROJECT_CREATE, AuditEntityType.PROJECT, 2L,
                AuditActor.USER, 1L, null);

        var result = auditService.find(
                new AuditLogFilter(AuditEntityType.TICKET, null, null, null), 1, 10);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.data().get(0).entityType()).isEqualTo(AuditEntityType.TICKET);
    }

    @Test
    void find_filtersByMultipleFieldsWithAnd() {
        auditService.record(AuditAction.TICKET_CREATE, AuditEntityType.TICKET, 1L,
                AuditActor.USER, 5L, null);
        auditService.record(AuditAction.TICKET_DELETE, AuditEntityType.TICKET, 1L,
                AuditActor.USER, 5L, null);
        auditService.record(AuditAction.TICKET_CREATE, AuditEntityType.TICKET, 2L,
                AuditActor.USER, 5L, null);

        var result = auditService.find(
                new AuditLogFilter(AuditEntityType.TICKET, 1L,
                        AuditAction.TICKET_CREATE, null), 1, 10);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.data().get(0).entityId()).isEqualTo(1L);
        assertThat(result.data().get(0).action()).isEqualTo(AuditAction.TICKET_CREATE);
    }

    @Test
    void find_ordersByCreatedAtDesc_newestFirst() {
        auditService.record(AuditAction.LOGIN, AuditEntityType.USER, 1L,
                AuditActor.USER, 1L, null);
        // Tiny delay to ensure distinct createdAt values
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        auditService.record(AuditAction.LOGOUT, AuditEntityType.USER, 1L,
                AuditActor.USER, 1L, null);

        var result = auditService.find(
                new AuditLogFilter(null, null, null, null), 1, 10);

        assertThat(result.data()).hasSize(2);
        assertThat(result.data().get(0).action()).isEqualTo(AuditAction.LOGOUT);
        assertThat(result.data().get(1).action()).isEqualTo(AuditAction.LOGIN);
    }
}