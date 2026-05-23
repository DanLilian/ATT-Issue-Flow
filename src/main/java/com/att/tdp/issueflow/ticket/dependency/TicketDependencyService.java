package com.att.tdp.issueflow.ticket.dependency;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.dependency.dto.AddDependencyRequest;
import com.att.tdp.issueflow.ticket.dependency.dto.DependencyResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operations on {@link TicketDependency}.
 *
 * Validation rules enforced here:
 *   1. Both tickets must exist (and not be soft-deleted — @SQLRestriction
 *      hides them naturally from findById).
 *   2. Self-blocking is rejected with a 409 (DB constraint
 *      ck_ticket_dep_no_self_block is the safety net).
 *   3. Cross-project blocking is rejected with a 409.
 *   4. Duplicate dependencies are rejected with a 409.
 *   5. Cycles (direct or transitive) are rejected with a 409.
 *
 * The "no DONE if unresolved blockers" rule lives in TicketService.update,
 * not here — it's part of the status-transition check, gated on the
 * specific transition to DONE.
 */
@Service
@Transactional(readOnly = true)
public class TicketDependencyService {

    private final TicketDependencyRepository dependencyRepository;
    private final TicketRepository ticketRepository;
    private final AuditService auditService;

    public TicketDependencyService(TicketDependencyRepository dependencyRepository,
                                   TicketRepository ticketRepository,
                                   AuditService auditService) {
        this.dependencyRepository = dependencyRepository;
        this.ticketRepository = ticketRepository;
        this.auditService = auditService;
    }

    public List<DependencyResponse> findByTicket(Long ticketId) {
        // Verify the ticket exists & isn't soft-deleted.
        if (ticketRepository.findById(ticketId).isEmpty()) {
            throw NotFoundException.of("Ticket", ticketId);
        }
        return dependencyRepository.findByTicketId(ticketId).stream()
                .map(DependencyResponse::from)
                .toList();
    }

    @Transactional
    public DependencyResponse add(Long ticketId, AddDependencyRequest req) {
        Long blockerId = req.blockedBy();

        // Rule 1: self-blocking. Fail early before any DB lookup.
        if (ticketId.equals(blockerId)) {
            throw new ConflictException("A ticket cannot block itself");
        }

        // Rule 1 (cont): both tickets must exist and not be soft-deleted.
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> NotFoundException.of("Ticket", ticketId));
        Ticket blocker = ticketRepository.findById(blockerId)
                .orElseThrow(() -> NotFoundException.of("Ticket", blockerId));

        // Rule 3: same project.
        if (!ticket.getProject().getId().equals(blocker.getProject().getId())) {
            throw new ConflictException(
                "Blocker must belong to the same project as the ticket");
        }

        // Rule 4: duplicate.
        if (dependencyRepository.existsByIdTicketIdAndIdBlockerTicketId(
                ticketId, blockerId)) {
            throw new ConflictException(
                "Dependency already exists: ticket %d is already blocked by %d"
                    .formatted(ticketId, blockerId));
        }

        // Rule 5: cycle detection. If `blocker` is (directly or transitively)
        // blocked by `ticket`, adding `ticket -> blocker` would close a cycle.
        if (wouldCreateCycle(ticketId, blockerId)) {
            throw new ConflictException(
                "Dependency would create a cycle: %d cannot block %d"
                    .formatted(blockerId, ticketId));
        }

        TicketDependency dep = new TicketDependency(ticket, blocker);
        TicketDependency saved = dependencyRepository.save(dep);

        auditService.record(
                AuditAction.DEPENDENCY_ADD,
                AuditEntityType.DEPENDENCY,
                ticketId,
                Map.of("blockerId", blockerId));

        return DependencyResponse.from(saved);
    }

    @Transactional
    public void remove(Long ticketId, Long blockerId) {
        TicketDependencyId id = new TicketDependencyId(ticketId, blockerId);
        if (!dependencyRepository.existsById(id)) {
            throw NotFoundException.of("Dependency", ticketId);
        }
        dependencyRepository.deleteById(id);

        auditService.record(
                AuditAction.DEPENDENCY_REMOVE,
                AuditEntityType.DEPENDENCY,
                ticketId,
                Map.of("blockerId", blockerId));
    }

    /**
     * BFS over the dependency graph starting from {@code candidateBlockerId}.
     * Returns true if {@code originId} appears as a transitive blocker —
     * meaning adding {@code originId -> candidateBlockerId} would close a cycle.
     *
     * Bounded by the total number of edges in the graph; safe for the
     * assignment scale. Production at large scale would use a SQL recursive
     * CTE or a graph DB.
     */
    private boolean wouldCreateCycle(Long originId, Long candidateBlockerId) {
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(candidateBlockerId);

        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (!visited.add(current)) continue;

            // Get all tickets that block `current`.
            List<TicketDependency> blockers =
                    dependencyRepository.findByTicketId(current);
            for (TicketDependency dep : blockers) {
                Long blockerId = dep.getBlocker().getId();
                if (blockerId.equals(originId)) {
                    return true; // cycle: origin appears as a blocker of candidate
                }
                queue.add(blockerId);
            }
        }
        return false;
    }
}