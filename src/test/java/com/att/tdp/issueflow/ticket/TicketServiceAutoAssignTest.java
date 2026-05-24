package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditActor;
import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.common.JpaConfig;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;
import com.att.tdp.issueflow.ticket.dto.TicketResponse;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice tests for the auto-assign behavior added in Phase 14.
 * Covers: assignee provided wins over auto-assign, no DEVELOPER in
 * system leaves assignee null, single DEVELOPER is picked, least-loaded
 * is picked, tie-break by createdAt, and the AUTO_ASSIGN audit row.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketService.class, AuditService.class, JpaConfig.class,
         ObjectMapper.class})
class TicketServiceAutoAssignTest {

    @Autowired TicketService ticketService;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @PersistenceContext EntityManager entityManager;

    private Long projectId;
    private User owner;

    @BeforeEach
    void seedAndClearAudit() {
        // Audit rows commit via REQUIRES_NEW, surviving prior test rollback.
        auditLogRepository.deleteAll();
        entityManager.flush();

        owner = userRepository.save(new User(
                "owner", "owner@x.com", "h", "Owner", UserRole.ADMIN));
        Project project = projectRepository.save(new Project("P", "d", owner));
        projectId = project.getId();
    }

    private CreateTicketRequest req(Long assigneeId) {
        return new CreateTicketRequest(
                "T", "d",
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                projectId, assigneeId, null);
    }

    // ─── explicit assignee wins over auto-assign ────────────────────────

    @Test
    void create_withExplicitAssignee_doesNotTriggerAutoAssign() {
        User dev = userRepository.save(new User(
                "dev", "dev@x.com", "h", "Dev", UserRole.DEVELOPER));
        entityManager.flush();

        TicketResponse created = ticketService.create(req(dev.getId()));
        entityManager.flush();
        entityManager.clear();

        assertThat(created.assigneeId()).isEqualTo(dev.getId());

        // No AUTO_ASSIGN audit row.
        List<AuditLog> autoAssignRows = auditLogRepository.findAll().stream()
                .filter(a -> a.getAction() == AuditAction.AUTO_ASSIGN)
                .toList();
        assertThat(autoAssignRows).isEmpty();
    }

    // ─── no DEVELOPER → null assignee, no audit ─────────────────────────

    @Test
    void create_withoutAssignee_andNoDevelopers_leavesAssigneeNull() {
        // Only the ADMIN owner exists. No DEVELOPER users at all.
        TicketResponse created = ticketService.create(req(null));
        entityManager.flush();
        entityManager.clear();

        assertThat(created.assigneeId()).isNull();

        List<AuditLog> autoAssignRows = auditLogRepository.findAll().stream()
                .filter(a -> a.getAction() == AuditAction.AUTO_ASSIGN)
                .toList();
        assertThat(autoAssignRows).isEmpty();
    }

    // ─── single developer picked + audit ────────────────────────────────

    @Test
    void create_withoutAssignee_picksSoleDeveloper_andAuditsAutoAssign() {
        User dev = userRepository.save(new User(
                "dev", "dev@x.com", "h", "Dev", UserRole.DEVELOPER));
        entityManager.flush();

        TicketResponse created = ticketService.create(req(null));
        entityManager.flush();
        entityManager.clear();

        assertThat(created.assigneeId()).isEqualTo(dev.getId());

        List<AuditLog> autoAssignRows = auditLogRepository.findAll().stream()
                .filter(a -> a.getAction() == AuditAction.AUTO_ASSIGN)
                .toList();
        assertThat(autoAssignRows).hasSize(1);
        AuditLog row = autoAssignRows.get(0);
        assertThat(row.getActor()).isEqualTo(AuditActor.SYSTEM);
        assertThat(row.getPerformedBy()).isNull();
        assertThat(row.getMetadataJson()).contains(String.valueOf(dev.getId()));
    }

    // ─── least-loaded picked ────────────────────────────────────────────

    @Test
    void create_withoutAssignee_picksLeastLoadedDeveloper() {
        User busy = userRepository.save(new User(
                "busy", "busy@x.com", "h", "Busy", UserRole.DEVELOPER));
        User free = userRepository.save(new User(
                "free", "free@x.com", "h", "Free", UserRole.DEVELOPER));
        entityManager.flush();

        // Pre-load busy with 2 open tickets in this project.
        ticketService.create(req(busy.getId()));
        ticketService.create(req(busy.getId()));
        entityManager.flush();
        entityManager.clear();

        // Now create without assignee — should go to 'free'.
        TicketResponse created = ticketService.create(req(null));

        assertThat(created.assigneeId()).isEqualTo(free.getId());
    }

    // ─── tie-break by createdAt ─────────────────────────────────────────

    @Test
    void create_withoutAssignee_tieBreakerIsOldestCreatedAt() {
        // Two devs, both with 0 open tickets. Older wins.
        User older = userRepository.save(new User(
                "older", "older@x.com", "h", "Older", UserRole.DEVELOPER));
        entityManager.flush();   // commit createdAt for 'older' first

        // Small sleep to guarantee distinct createdAt ordering on H2.
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}

        User newer = userRepository.save(new User(
                "newer", "newer@x.com", "h", "Newer", UserRole.DEVELOPER));
        entityManager.flush();
        entityManager.clear();

        TicketResponse created = ticketService.create(req(null));

        assertThat(created.assigneeId()).isEqualTo(older.getId());
    }

    // ─── DONE tickets don't count toward workload ───────────────────────

    @Test
    void create_withoutAssignee_excludesDoneTicketsFromWorkload() {
        User devA = userRepository.save(new User(
                "devA", "a@x.com", "h", "A", UserRole.DEVELOPER));
        User devB = userRepository.save(new User(
                "devB", "b@x.com", "h", "B", UserRole.DEVELOPER));
        entityManager.flush();

        // devA has 3 DONE tickets — should not count.
        for (int i = 0; i < 3; i++) {
            TicketResponse t = ticketService.create(req(devA.getId()));
            Ticket entity = ticketRepository.findById(t.id()).orElseThrow();
            entity.transitionTo(TicketStatus.IN_PROGRESS);
            entity.transitionTo(TicketStatus.IN_REVIEW);
            entity.transitionTo(TicketStatus.DONE);
            ticketRepository.save(entity);
        }
        entityManager.flush();
        entityManager.clear();

        // devB has 1 open ticket.
        ticketService.create(req(devB.getId()));
        entityManager.flush();
        entityManager.clear();

        // Both have workload 0 (devA) vs 1 (devB) → devA picked.
        TicketResponse created = ticketService.create(req(null));

        assertThat(created.assigneeId()).isEqualTo(devA.getId());
    }
}