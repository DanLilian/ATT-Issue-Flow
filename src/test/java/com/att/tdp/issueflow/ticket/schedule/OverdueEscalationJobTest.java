package com.att.tdp.issueflow.ticket.schedule;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditActor;
import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.common.JpaConfig;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.TicketService;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import com.att.tdp.issueflow.ticket.dependency.TicketDependencyRepository;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test for the escalation logic. Tests TicketService.escalateAllOverdue
 * directly (the job is a thin scheduling wrapper that we don't unit-test;
 * its only job is to call this method).
 *
 * Audit table is cleared in @BeforeEach because AuditService.record uses
 * REQUIRES_NEW and commits outside the test transaction (same pattern as
 * Phase 9's AuditServiceTest).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketService.class, AuditService.class, JpaConfig.class,
         ObjectMapper.class})
class OverdueEscalationJobTest {

    @Autowired TicketService ticketService;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired TicketDependencyRepository dependencyRepository;
    @PersistenceContext EntityManager entityManager;

    private Project project;

    @BeforeEach
    void seedAndClearAudit() {
        // Clear audit rows from prior tests (REQUIRES_NEW commits survive
        // the test transaction rollback).
        auditLogRepository.deleteAll();
        entityManager.flush();

        User owner = userRepository.save(new User(
                "owner", "owner@x.com", "h", "Owner", UserRole.ADMIN));
        project = projectRepository.save(new Project("P", "d", owner));
    }

    private Ticket overdueTicket(TicketPriority priority, boolean overdue) {
        Ticket t = new Ticket("T", "d",
                TicketStatus.TODO, priority, TicketType.BUG,
                project, null,
                Instant.now().minus(1, ChronoUnit.DAYS));  // due yesterday
        // Save first so id exists.
        Ticket saved = ticketRepository.save(t);
        if (overdue) {
            // Use entity method to set isOverdue=true; transitionTo would
            // not change it, so we use reflection-free approach via autoEscalate
            // at CRITICAL. For non-CRITICAL tickets we can't easily flip the
            // flag without exposing a setter. The test uses CRITICAL+overdue
            // separately via this path.
            // For non-CRITICAL tickets, this method just saves and returns.
        }
        entityManager.flush();
        entityManager.clear();
        return saved;
    }

    // ─── full escalation cycle ──────────────────────────────────────────

    @Test
    void escalate_walksTicketThroughFullCycle() {
        // Start at LOW with a due date in the past, isOverdue=false.
        Ticket t = ticketRepository.save(new Ticket("T", "d",
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                project, null,
                Instant.now().minus(1, ChronoUnit.DAYS)));
        entityManager.flush();
        entityManager.clear();
        Long ticketId = t.getId();

        // Pass 1: LOW -> MEDIUM
        ticketService.escalateAllOverdue();
        entityManager.flush();
        entityManager.clear();
        assertThat(ticketRepository.findById(ticketId).orElseThrow().getPriority())
                .isEqualTo(TicketPriority.MEDIUM);

        // Pass 2: MEDIUM -> HIGH
        ticketService.escalateAllOverdue();
        entityManager.flush();
        entityManager.clear();
        assertThat(ticketRepository.findById(ticketId).orElseThrow().getPriority())
                .isEqualTo(TicketPriority.HIGH);

        // Pass 3: HIGH -> CRITICAL
        ticketService.escalateAllOverdue();
        entityManager.flush();
        entityManager.clear();
        assertThat(ticketRepository.findById(ticketId).orElseThrow().getPriority())
                .isEqualTo(TicketPriority.CRITICAL);
        assertThat(ticketRepository.findById(ticketId).orElseThrow().isOverdue())
                .isFalse();

        // Pass 4: CRITICAL stays, isOverdue becomes true.
        ticketService.escalateAllOverdue();
        entityManager.flush();
        entityManager.clear();
        Ticket terminal = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(terminal.getPriority()).isEqualTo(TicketPriority.CRITICAL);
        assertThat(terminal.isOverdue()).isTrue();
    }

    // ─── idempotency ────────────────────────────────────────────────────

    @Test
    void escalate_isIdempotent_onTerminalState() {
        // Walk the ticket all the way to CRITICAL+overdue.
        Ticket t = ticketRepository.save(new Ticket("T", "d",
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                project, null,
                Instant.now().minus(1, ChronoUnit.DAYS)));
        entityManager.flush();
        for (int i = 0; i < 4; i++) {
            ticketService.escalateAllOverdue();
            entityManager.flush();
            entityManager.clear();
        }

        // Now in CRITICAL+overdue. Count audit rows.
        long auditCountBefore = auditLogRepository
                .findAll().stream()
                .filter(a -> a.getAction() == AuditAction.AUTO_ESCALATE)
                .count();

        // Run several more times.
        for (int i = 0; i < 3; i++) {
            int n = ticketService.escalateAllOverdue();
            assertThat(n).isEqualTo(0);  // nothing escalated
            entityManager.flush();
            entityManager.clear();
        }

        long auditCountAfter = auditLogRepository
                .findAll().stream()
                .filter(a -> a.getAction() == AuditAction.AUTO_ESCALATE)
                .count();
        assertThat(auditCountAfter).isEqualTo(auditCountBefore);

        // Ticket state unchanged.
        Ticket finalState = ticketRepository.findById(t.getId()).orElseThrow();
        assertThat(finalState.getPriority()).isEqualTo(TicketPriority.CRITICAL);
        assertThat(finalState.isOverdue()).isTrue();
    }

    // ─── manual priority change resets escalation ───────────────────────

    @Test
    void manualPriorityChange_resetsOverdue_andResumesEscalationOnNextScan() {
        // Get to CRITICAL+overdue.
        Ticket t = ticketRepository.save(new Ticket("T", "d",
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                project, null,
                Instant.now().minus(1, ChronoUnit.DAYS)));
        entityManager.flush();
        for (int i = 0; i < 4; i++) {
            ticketService.escalateAllOverdue();
            entityManager.flush();
            entityManager.clear();
        }

        // User manually resets priority via the entity method. (In production
        // this happens via TicketService.update with the priority field set.)
        Ticket loaded = ticketRepository.findById(t.getId()).orElseThrow();
        loaded.updatePriorityManually(TicketPriority.LOW);
        entityManager.flush();
        entityManager.clear();

        // isOverdue should be cleared.
        Ticket afterReset = ticketRepository.findById(t.getId()).orElseThrow();
        assertThat(afterReset.getPriority()).isEqualTo(TicketPriority.LOW);
        assertThat(afterReset.isOverdue()).isFalse();

        // Next scan resumes the climb: LOW -> MEDIUM.
        ticketService.escalateAllOverdue();
        entityManager.flush();
        entityManager.clear();
        Ticket resumed = ticketRepository.findById(t.getId()).orElseThrow();
        assertThat(resumed.getPriority()).isEqualTo(TicketPriority.MEDIUM);
    }

    // ─── selectivity ────────────────────────────────────────────────────

    @Test
    void escalate_ignoresTicketsWithFutureDueDate() {
        ticketRepository.save(new Ticket("Future", "d",
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                project, null,
                Instant.now().plus(7, ChronoUnit.DAYS)));   // due next week
        entityManager.flush();

        int escalated = ticketService.escalateAllOverdue();

        assertThat(escalated).isEqualTo(0);
    }

    @Test
    void escalate_ignoresTicketsWithNoDueDate() {
        ticketRepository.save(new Ticket("NoDueDate", "d",
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                project, null,
                null));   // no due date
        entityManager.flush();

        int escalated = ticketService.escalateAllOverdue();

        assertThat(escalated).isEqualTo(0);
    }

    @Test
    void escalate_ignoresDoneTickets() {
        // Create overdue at LOW, then move it to DONE via the service so
        // status transitions work properly.
        // For brevity, use a workaround: save directly at DONE with overdue due date.
        // The query filters status <> DONE, so this verifies the SQL filter.
        Ticket t = new Ticket("Done", "d",
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                project, null,
                Instant.now().minus(1, ChronoUnit.DAYS));
        // Walk to DONE through allowed transitions, ignoring the test order:
        t = ticketRepository.save(t);
        t.transitionTo(TicketStatus.IN_PROGRESS);
        t.transitionTo(TicketStatus.IN_REVIEW);
        t.transitionTo(TicketStatus.DONE);
        ticketRepository.save(t);
        entityManager.flush();

        int escalated = ticketService.escalateAllOverdue();

        assertThat(escalated).isEqualTo(0);
    }

    // ─── audit row shape ────────────────────────────────────────────────

    @Test
    void escalate_emitsAuditRow_withActorSystem() {
        ticketRepository.save(new Ticket("T", "d",
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                project, null,
                Instant.now().minus(1, ChronoUnit.DAYS)));
        entityManager.flush();

        ticketService.escalateAllOverdue();
        entityManager.flush();
        entityManager.clear();

        List<AuditLog> escalations = auditLogRepository.findAll().stream()
                .filter(a -> a.getAction() == AuditAction.AUTO_ESCALATE)
                .toList();

        assertThat(escalations).hasSize(1);
        AuditLog row = escalations.get(0);
        assertThat(row.getActor()).isEqualTo(AuditActor.SYSTEM);
        assertThat(row.getPerformedBy()).isNull();
        assertThat(row.getMetadataJson()).contains("LOW", "MEDIUM");
    }
}