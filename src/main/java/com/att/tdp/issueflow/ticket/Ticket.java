package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.BaseEntity;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

@Entity
@Table(name = "tickets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@SQLDelete(sql = "UPDATE tickets SET deleted_at = NOW() WHERE id = ?")
public class Ticket extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 5000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TicketPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TicketType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Nullable: tickets can be unassigned (e.g. no DEVELOPER on the project). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    /** Optional. Drives auto-escalation; nullable means no escalation applies. */
    @Column(name = "due_date")
    private Instant dueDate;

    /**
     * Set to true once the ticket has reached CRITICAL while still overdue.
     * Cleared on any manual priority change. Visible in all GET responses.
     */
    @Column(name = "is_overdue", nullable = false)
    private boolean isOverdue = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Optimistic-lock token. Hibernate increments on every update and rejects
     * a save where the in-memory version is older than the DB row. Maps the
     * "two users can't update the same ticket simultaneously" requirement.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    public Ticket(String title, String description,
                  TicketStatus status, TicketPriority priority, TicketType type,
                  Project project, User assignee, Instant dueDate) {
        this.title = title;
        this.description = description;
        this.status = status;
        this.priority = priority;
        this.type = type;
        this.project = project;
        this.assignee = assignee;
        this.dueDate = dueDate;
    }

    // ─── Domain behavior ────────────────────────────────────────────────

    /**
     * Guard rail invoked by the service before any update.
     * A DONE ticket is terminal and may not be modified except by restore.
     */
    public void assertNotTerminal() {
        if (status.isTerminal()) {
            throw new IllegalStateException("Ticket is DONE and cannot be updated");
        }
    }

    /**
     * Enforces the forward-only lifecycle. Throws if {@code next} is not a
     * permitted transition from the current status. Callers must additionally
     * verify there are no unresolved blockers before transitioning to DONE
     * (that check requires repository access and lives in the service).
     */
    public void transitionTo(TicketStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                "Illegal status transition: %s -> %s".formatted(status, next));
        }
        this.status = next;
    }

    public void updateContent(String title, String description) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
    }

    public void updateDueDate(Instant dueDate) {
        this.dueDate = dueDate;
    }

    public void assignTo(User user) {
        this.assignee = user;
    }

    /**
     * Manual priority change resets the auto-escalation state (PDF 3.7).
     * Used by the service when the user PATCHes the priority field.
     */
    public void updatePriorityManually(TicketPriority newPriority) {
        this.priority = newPriority;
        this.isOverdue = false;
    }

    /**
     * Auto-escalation path used by the scheduler. Bumps priority one step
     * up the ladder. When already at CRITICAL, marks the ticket overdue.
     * Idempotent: calling on a CRITICAL+overdue ticket is a no-op.
     */
    public void autoEscalate() {
        if (priority.isMax()) {
            this.isOverdue = true;
            return;
        }
        this.priority = priority.escalate();
    }

    public void markDeleted() { this.deletedAt = Instant.now(); }
    public void restore() { this.deletedAt = null; }
    public boolean isDeleted() { return deletedAt != null; }
}