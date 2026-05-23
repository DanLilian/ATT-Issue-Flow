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
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditService;
import java.util.Map;
import java.util.Objects;
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
    private final AuditService auditService;

    public TicketService(TicketRepository ticketRepository,
                         ProjectRepository projectRepository,
                         UserRepository userRepository,
                         AuditService auditService) {
        this.ticketRepository = ticketRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
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
        Project project = projectRepository.findById(req.projectId())
                .orElseThrow(() -> NotFoundException.of("Project", req.projectId()));

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

        auditService.record(
                AuditAction.TICKET_CREATE,
                AuditEntityType.TICKET,
                saved.getId(),
                Map.of("title", saved.getTitle()));
        // TODO Phase 14: if auto-assigned, audit AUTO_ASSIGN with actor=SYSTEM.

        return TicketResponse.from(saved);
    }

    @Transactional
    public void update(Long id, UpdateTicketRequest req) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Ticket", id));

        try {
            ticket.assertNotTerminal();
        } catch (IllegalStateException ex) {
            throw new ConflictException(ex.getMessage());
        }

        // Snapshot "before" state for granular audit events.
        TicketStatus oldStatus = ticket.getStatus();
        TicketPriority oldPriority = ticket.getPriority();
        Long oldAssigneeId = ticket.getAssignee() != null
                ? ticket.getAssignee().getId()
                : null;

        if (req.status() != null && req.status() != ticket.getStatus()) {
            try {
                ticket.transitionTo(req.status());
            } catch (IllegalStateException ex) {
                throw new ConflictException(ex.getMessage());
            }
        }

        if (req.priority() != null && req.priority() != ticket.getPriority()) {
            ticket.updatePriorityManually(req.priority());
        }

        if (req.assigneeId() != null) {
            User newAssignee = userRepository.findById(req.assigneeId())
                    .orElseThrow(() -> NotFoundException.of("User", req.assigneeId()));
            ticket.assignTo(newAssignee);
        }

        if (req.dueDate() != null) {
            ticket.updateDueDate(req.dueDate());
        }

        ticket.updateContent(req.title(), req.description());

        // Audit: always emit TICKET_UPDATE.
        auditService.record(AuditAction.TICKET_UPDATE, AuditEntityType.TICKET, id, null);

        // Plus granular events for the specific transitions that consumers
        // care most about.
        if (oldStatus != ticket.getStatus()) {
            auditService.record(
                    AuditAction.TICKET_STATUS_CHANGE,
                    AuditEntityType.TICKET,
                    id,
                    Map.of("from", oldStatus.name(), "to", ticket.getStatus().name()));
        }
        if (oldPriority != ticket.getPriority()) {
            auditService.record(
                    AuditAction.TICKET_PRIORITY_CHANGE,
                    AuditEntityType.TICKET,
                    id,
                    Map.of("from", oldPriority.name(), "to", ticket.getPriority().name()));
        }
        Long newAssigneeId = ticket.getAssignee() != null
                ? ticket.getAssignee().getId()
                : null;
        if (!Objects.equals(oldAssigneeId, newAssigneeId)) {
            auditService.record(
                    AuditAction.TICKET_ASSIGN,
                    AuditEntityType.TICKET,
                    id,
                    newAssigneeId != null
                            ? Map.of("assigneeId", newAssigneeId)
                            : null);
        }
    }

    @Transactional
    public void softDelete(Long id) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Ticket", id));

        ticket.markDeleted();

        auditService.record(
                AuditAction.TICKET_DELETE,
                AuditEntityType.TICKET,
                id,
                null);
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

        auditService.record(
                AuditAction.TICKET_RESTORE,
                AuditEntityType.TICKET,
                id,
                null);
    }
}