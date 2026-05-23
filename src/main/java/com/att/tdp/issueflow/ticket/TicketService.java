package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;
import com.att.tdp.issueflow.ticket.dto.TicketResponse;
import com.att.tdp.issueflow.ticket.dto.UpdateTicketRequest;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Business operations for {@link Ticket}.
 *
 * Convergence point for several PDF rules:
 *   - Forward-only status lifecycle (entity-enforced via transitionTo).
 *   - DONE ticket lock (entity-enforced via assertNotTerminal).
 *   - Manual priority change resets isOverdue (entity-enforced via
 *     updatePriorityManually).
 *   - Optimistic locking via @Version (entity-declared; handler in
 *     GlobalExceptionHandler maps the failure to 409).
 *   - Soft delete via entity.markDeleted() + @SQLRestriction.
 *
 * Auto-assignment when assigneeId is absent is intentionally NOT
 * implemented here — added in Phase 14. The TODO marks the hook.
 *
 * Audit-log writes deferred to Phase 9.
 */
@Service
@Transactional(readOnly = true)
public class TicketService {

    private final TicketRepository ticketRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public TicketService(TicketRepository ticketRepository,
                         ProjectRepository projectRepository,
                         UserRepository userRepository) {
        this.ticketRepository = ticketRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    public List<TicketResponse> findByProject(Long projectId) {
        // Ensure the project exists & isn't soft-deleted before listing.
        // Without this, requesting tickets for a non-existent project just
        // returns []; explicit 404 is clearer.
        if (projectRepository.findById(projectId).isEmpty()) {
            throw NotFoundException.of("Project", projectId);
        }
        return ticketRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(TicketResponse::from)
                .toList();
    }

    public TicketResponse findById(Long id) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Ticket", id));
        return TicketResponse.from(ticket);
    }

    @Transactional
    public TicketResponse create(CreateTicketRequest req) {
        // Project must exist and be active (@SQLRestriction excludes soft-deleted).
        Project project = projectRepository.findById(req.projectId())
                .orElseThrow(() -> NotFoundException.of("Project", req.projectId()));

        // Assignee optional; if provided, must exist.
        User assignee = null;
        if (req.assigneeId() != null) {
            assignee = userRepository.findById(req.assigneeId())
                    .orElseThrow(() -> NotFoundException.of("User", req.assigneeId()));
        }
        // TODO Phase 14: if assignee is null, run auto-assignment by workload.

        Ticket ticket = new Ticket(
                req.title(), req.description(),
                req.status(), req.priority(), req.type(),
                project, assignee, req.dueDate());

        Ticket saved = ticketRepository.save(ticket);

        // TODO Phase 9: auditService.record(TICKET_CREATE, TICKET, saved.getId(), ...);
        // TODO Phase 14: if auto-assigned, audit AUTO_ASSIGN with actor=SYSTEM.

        return TicketResponse.from(saved);
    }

    @Transactional
    public void update(Long id, UpdateTicketRequest req) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Ticket", id));

        // PDF: "A ticket can't be updated once it's DONE."
        // This check comes first — DONE is terminal even for no-op patches.
        try {
            ticket.assertNotTerminal();
        } catch (IllegalStateException ex) {
            throw new ConflictException(ex.getMessage());
        }

        // Status transition: forward-only. The entity enforces; we translate
        // its IllegalStateException to a 409 with a clear message.
        if (req.status() != null && req.status() != ticket.getStatus()) {
            try {
                ticket.transitionTo(req.status());
            } catch (IllegalStateException ex) {
                throw new ConflictException(ex.getMessage());
            }
        }

        // Manual priority change resets isOverdue (PDF 3.7).
        if (req.priority() != null && req.priority() != ticket.getPriority()) {
            ticket.updatePriorityManually(req.priority());
        }

        // Assignee: only change when the field is present in the request.
        // Per the PATCH semantics defined in CreateTicketRequest's note,
        // null means 'don't change'.
        if (req.assigneeId() != null) {
            User newAssignee = userRepository.findById(req.assigneeId())
                    .orElseThrow(() -> NotFoundException.of("User", req.assigneeId()));
            ticket.assignTo(newAssignee);
        }

        if (req.dueDate() != null) {
            ticket.updateDueDate(req.dueDate());
        }

        // Title and description: nullable -> 'don't change'. The entity
        // method already ignores nulls.
        ticket.updateContent(req.title(), req.description());

        // No save() — managed entity, dirty checking on commit. The @Version
        // bump happens at flush. If another transaction committed first, an
        // OptimisticLockingFailureException fires and is mapped to 409 by
        // the global handler.

        // TODO Phase 9: audit (TICKET_UPDATE, plus TICKET_STATUS_CHANGE /
        // TICKET_PRIORITY_CHANGE / TICKET_ASSIGN as appropriate).
    }

    @Transactional
    public void softDelete(Long id) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Ticket", id));

        ticket.markDeleted();

        // TODO Phase 9: auditService.record(TICKET_DELETE, TICKET, id, ...);
    }

    /** ADMIN-only. List soft-deleted tickets for a project. */
    public List<TicketResponse> findAllDeletedByProject(Long projectId) {
        return ticketRepository.findAllDeletedByProjectId(projectId).stream()
                .map(TicketResponse::from)
                .toList();
    }

    /**
     * ADMIN-only. Restore a soft-deleted ticket.
     *
     * Same semantics as Phase 6's project restore: 404 when the id doesn't
     * exist, 409 when the ticket exists but isn't deleted.
     */
    @Transactional
    public void restore(Long id) {
        Ticket ticket = ticketRepository.findDeletedById(id)
                .orElseThrow(() -> {
                    if (ticketRepository.findById(id).isPresent()) {
                        return new ConflictException(
                            "Ticket with id %d is not deleted".formatted(id));
                    }
                    return NotFoundException.of("Ticket", id);
                });

        ticket.restore();

        // TODO Phase 9: auditService.record(TICKET_RESTORE, TICKET, id, ...);
    }
}