package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.common.JpaConfig;
import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.dependency.TicketDependency;
import com.att.tdp.issueflow.ticket.dependency.TicketDependencyRepository;
import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;
import com.att.tdp.issueflow.ticket.dto.TicketResponse;
import com.att.tdp.issueflow.ticket.dto.UpdateTicketRequest;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import jakarta.persistence.OptimisticLockException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice test for TicketService with a real (H2) repository.
 *
 * Focus areas — the rules that need real behavioral verification:
 *   - Forward-only status transitions (allowed and rejected paths).
 *   - DONE ticket lock against all PATCH operations.
 *   - Optimistic locking via @Version on concurrent modification.
 *   - Manual priority change clears isOverdue (the PDF 3.7 invariant).
 *   - Soft delete invisibility via @SQLRestriction (with flush/clear).
 *   - Owner/assignee validation on create.
 *   - Cannot create a ticket against a soft-deleted project.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketService.class, AuditService.class, JpaConfig.class,
         com.fasterxml.jackson.databind.ObjectMapper.class})
class TicketServiceTest {

    @Autowired TicketService ticketService;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired TicketDependencyRepository dependencyRepository;
    @PersistenceContext EntityManager entityManager;

    private Long projectId;
    private Long developerId;

    @BeforeEach
    void seedFixture() {
        User owner = userRepository.save(new User(
                "owner", "owner@x.com", "hash", "Owner", UserRole.ADMIN));
        User dev = userRepository.save(new User(
                "dev", "dev@x.com", "hash", "Dev", UserRole.DEVELOPER));

        Project project = projectRepository.save(new Project(
                "Project", "desc", owner));

        this.projectId = project.getId();
        this.developerId = dev.getId();
    }

    private CreateTicketRequest validCreate() {
        return new CreateTicketRequest(
                "Title", "desc",
                TicketStatus.TODO, TicketPriority.MEDIUM, TicketType.BUG,
                projectId, developerId, null);
    }

    // ─── Create ─────────────────────────────────────────────────────────

    @Test
    void create_persistsTicket_withAllFields() {
        Instant due = Instant.now().plus(7, ChronoUnit.DAYS);
        CreateTicketRequest req = new CreateTicketRequest(
                "Bug X", "details",
                TicketStatus.TODO, TicketPriority.HIGH, TicketType.BUG,
                projectId, developerId, due);

        TicketResponse created = ticketService.create(req);

        assertThat(created.id()).isNotNull();
        assertThat(created.title()).isEqualTo("Bug X");
        assertThat(created.status()).isEqualTo(TicketStatus.TODO);
        assertThat(created.projectId()).isEqualTo(projectId);
        assertThat(created.assigneeId()).isEqualTo(developerId);
        assertThat(created.dueDate()).isEqualTo(due);
        assertThat(created.isOverdue()).isFalse();
    }

    @Test
    void create_throwsNotFound_whenProjectMissing() {
        CreateTicketRequest req = new CreateTicketRequest(
                "T", "d", TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                99_999L, developerId, null);

        assertThatThrownBy(() -> ticketService.create(req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Project with id 99999");
    }

    @Test
    void create_throwsNotFound_whenAssigneeMissing() {
        CreateTicketRequest req = new CreateTicketRequest(
                "T", "d", TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                projectId, 99_999L, null);

        assertThatThrownBy(() -> ticketService.create(req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User with id 99999");
    }

    @Test
    void create_allowsNullAssignee() {
        CreateTicketRequest req = new CreateTicketRequest(
                "T", "d", TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                projectId, null, null);

        TicketResponse created = ticketService.create(req);

        assertThat(created.assigneeId()).isNull();
    }

    @Test
    void create_rejectsTicketAgainstSoftDeletedProject() {
        // Soft delete the project.
        Project project = projectRepository.findById(projectId).orElseThrow();
        project.markDeleted();
        entityManager.flush();
        entityManager.clear();

        // @SQLRestriction now hides the project from findById.
        assertThatThrownBy(() -> ticketService.create(validCreate()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Project");
    }

    // ─── Status transitions ─────────────────────────────────────────────

    @Test
    void update_allowsForwardStatusTransition() {
        TicketResponse t = ticketService.create(validCreate());

        ticketService.update(t.id(), new UpdateTicketRequest(
                null, null, TicketStatus.IN_PROGRESS, null, null, null));

        assertThat(ticketService.findById(t.id()).status())
                .isEqualTo(TicketStatus.IN_PROGRESS);
    }

    @Test
    void update_rejectsBackwardStatusTransition() {
        TicketResponse t = ticketService.create(validCreate());
        ticketService.update(t.id(), new UpdateTicketRequest(
                null, null, TicketStatus.IN_PROGRESS, null, null, null));

        assertThatThrownBy(() -> ticketService.update(t.id(), new UpdateTicketRequest(
                null, null, TicketStatus.TODO, null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Illegal status transition");
    }

    @Test
    void update_rejectsSkippingStatuses() {
        TicketResponse t = ticketService.create(validCreate());

        // TODO -> DONE is illegal (must go through IN_PROGRESS, IN_REVIEW)
        assertThatThrownBy(() -> ticketService.update(t.id(), new UpdateTicketRequest(
                null, null, TicketStatus.DONE, null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Illegal status transition");
    }

    // ─── DONE lock ──────────────────────────────────────────────────────

    @Test
    void update_rejectsAllChanges_whenTicketIsDone() {
        TicketResponse t = ticketService.create(validCreate());
        // Walk through the lifecycle to DONE.
        ticketService.update(t.id(), new UpdateTicketRequest(
                null, null, TicketStatus.IN_PROGRESS, null, null, null));
        ticketService.update(t.id(), new UpdateTicketRequest(
                null, null, TicketStatus.IN_REVIEW, null, null, null));
        ticketService.update(t.id(), new UpdateTicketRequest(
                null, null, TicketStatus.DONE, null, null, null));

        // Even a benign title change must be rejected.
        assertThatThrownBy(() -> ticketService.update(t.id(), new UpdateTicketRequest(
                "renamed", null, null, null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("DONE");
    }

    @Test
        void update_rejectsTransitionToDone_whenUnresolvedBlockersExist() {
        // Create the ticket and walk it to IN_REVIEW (legal forward transitions).
        TicketResponse t = ticketService.create(validCreate());
        ticketService.update(t.id(), new UpdateTicketRequest(
                null, null, TicketStatus.IN_PROGRESS, null, null, null));
        ticketService.update(t.id(), new UpdateTicketRequest(
                null, null, TicketStatus.IN_REVIEW, null, null, null));

        // Create a blocker ticket in the same project, leave it at TODO.
        TicketResponse blocker = ticketService.create(validCreate());

        // Wire up the dependency directly via the repository (the service is
        // tested separately in TicketDependencyServiceTest).
        Ticket ticketEntity = ticketRepository.findById(t.id()).orElseThrow();
        Ticket blockerEntity = ticketRepository.findById(blocker.id()).orElseThrow();
        dependencyRepository.save(new TicketDependency(ticketEntity, blockerEntity));
        entityManager.flush();
        entityManager.clear();

        // Attempt to move the blocked ticket to DONE — should be rejected.
        assertThatThrownBy(() -> ticketService.update(t.id(), new UpdateTicketRequest(
                null, null, TicketStatus.DONE, null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("unresolved blockers");
        }
    // ─── Manual priority change resets isOverdue ────────────────────────

    @Test
    void update_priorityChange_clearsIsOverdue() {
        TicketResponse t = ticketService.create(validCreate());

        // Force the ticket into the 'CRITICAL + overdue' state directly.
        Ticket ticket = ticketRepository.findById(t.id()).orElseThrow();
        // Walk it to CRITICAL via the entity's auto-escalate path so we don't
        // need a public setter — escalate(LOW) reaches CRITICAL after 3 calls.
        // Simpler: use updatePriorityManually then a separate state mutation.
        // Cleanest: bypass via reflection? No — use the autoEscalate API.
        ticket.autoEscalate(); // MEDIUM -> HIGH (was MEDIUM from validCreate)
        ticket.autoEscalate(); // HIGH -> CRITICAL
        ticket.autoEscalate(); // CRITICAL + isOverdue=true
        entityManager.flush();
        entityManager.clear();

        assertThat(ticketRepository.findById(t.id()).orElseThrow().isOverdue())
                .isTrue();

        // Manual priority change.
        ticketService.update(t.id(), new UpdateTicketRequest(
                null, null, null, TicketPriority.LOW, null, null));

        TicketResponse updated = ticketService.findById(t.id());
        assertThat(updated.priority()).isEqualTo(TicketPriority.LOW);
        assertThat(updated.isOverdue()).isFalse();
    }

    // ─── Optimistic locking ─────────────────────────────────────────────

    @Test
    void update_concurrentModification_triggersOptimisticLockingFailure() {
        TicketResponse t = ticketService.create(validCreate());
        Long ticketId = t.id();
        entityManager.flush();
        entityManager.clear();

        // Load the same ticket into two separate entity instances.
        // To trigger optimistic lock, we mutate both based on the SAME
        // initial version and try to flush both. The second flush sees the
        // version bumped by the first and throws.
        Ticket loadedA = entityManager.find(Ticket.class, ticketId);
        entityManager.detach(loadedA);

        Ticket loadedB = entityManager.find(Ticket.class, ticketId);

        // Mutate and flush B first — version goes from 0 to 1 in the DB.
        loadedB.updateContent("from-B", null);
        entityManager.flush();

        // Now try to re-attach and save A, which still has version=0.
        // merge() detects the stale version and throws.
        loadedA.updateContent("from-A", null);

        // JPA's OptimisticLockException is what Hibernate throws directly at flush
        // time; Spring's OptimisticLockingFailureException is the translated wrapper
        // at transaction commit. This test exercises the flush path; the HTTP-layer
        // translation to 409 is verified in TicketControllerTest.
        assertThatThrownBy(() -> {
            entityManager.merge(loadedA);
            entityManager.flush();
        }).isInstanceOf(OptimisticLockException.class);
    }

    // ─── Soft delete + restore ──────────────────────────────────────────

    @Test
    void softDelete_hidesTicket_fromStandardReads() {
        TicketResponse t = ticketService.create(validCreate());

        ticketService.softDelete(t.id());
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> ticketService.findById(t.id()))
                .isInstanceOf(NotFoundException.class);
        assertThat(ticketRepository.findById(t.id())).isEmpty();
        assertThat(ticketRepository.findDeletedById(t.id())).isPresent();
    }

    @Test
    void findAllDeletedByProject_returnsOnlySoftDeleted() {
        TicketResponse active  = ticketService.create(validCreate());
        TicketResponse deleted = ticketService.create(validCreate());
        ticketService.softDelete(deleted.id());
        entityManager.flush();
        entityManager.clear();

        var result = ticketService.findAllDeletedByProject(projectId);

        assertThat(result).extracting(TicketResponse::id).containsExactly(deleted.id());
        assertThat(result).extracting(TicketResponse::id).doesNotContain(active.id());
    }

    @Test
    void restore_undeletesTicket() {
        TicketResponse t = ticketService.create(validCreate());
        ticketService.softDelete(t.id());
        entityManager.flush();
        entityManager.clear();

        ticketService.restore(t.id());
        entityManager.flush();
        entityManager.clear();

        assertThat(ticketService.findById(t.id()).id()).isEqualTo(t.id());
    }

    @Test
    void restore_throwsConflict_whenTicketIsNotDeleted() {
        TicketResponse t = ticketService.create(validCreate());

        assertThatThrownBy(() -> ticketService.restore(t.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not deleted");
    }

    // ─── findByProject ──────────────────────────────────────────────────

    @Test
    void findByProject_throwsNotFound_forMissingProject() {
        assertThatThrownBy(() -> ticketService.findByProject(99_999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void findByProject_excludesSoftDeletedTickets() {
        TicketResponse keep = ticketService.create(validCreate());
        TicketResponse drop = ticketService.create(validCreate());
        ticketService.softDelete(drop.id());
        entityManager.flush();
        entityManager.clear();

        var result = ticketService.findByProject(projectId);

        assertThat(result).extracting(TicketResponse::id).containsExactly(keep.id());
    }
}