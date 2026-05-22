package com.att.tdp.issueflow.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    // Inherited findById / findAll auto-filter soft-deleted tickets.

    List<Ticket> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /**
     * Bypasses @SQLRestriction. Used by ADMIN-only
     * GET /tickets/deleted?projectId=...
     */
    @Query(value = "SELECT * FROM tickets " +
                   "WHERE project_id = :projectId AND deleted_at IS NOT NULL " +
                   "ORDER BY deleted_at DESC",
           nativeQuery = true)
    List<Ticket> findAllDeletedByProjectId(@Param("projectId") Long projectId);

    /** Bypasses @SQLRestriction. Used by POST /tickets/{id}/restore. */
    @Query(value = "SELECT * FROM tickets WHERE id = :id AND deleted_at IS NOT NULL",
           nativeQuery = true)
    Optional<Ticket> findDeletedById(@Param("id") Long id);

    /**
     * Scan target for the overdue-escalation scheduler (Phase 13).
     * Returns tickets that have a due date, are past due, are not yet at
     * CRITICAL+overdue (the terminal escalated state), and are not DONE.
     */
    @Query("""
        SELECT t FROM Ticket t
        WHERE t.dueDate IS NOT NULL
          AND t.dueDate < :now
          AND t.status <> com.att.tdp.issueflow.ticket.TicketStatus.DONE
          AND NOT (t.priority = com.att.tdp.issueflow.ticket.TicketPriority.CRITICAL
                   AND t.isOverdue = true)
        """)
    List<Ticket> findEscalationCandidates(@Param("now") Instant now);

    /**
     * Workload aggregation: per developer, count of non-DONE tickets in
     * a given project. Used by GET /projects/{projectId}/workload and by
     * auto-assignment to find the least-loaded developer.
     *
     * Returns Object[]{userId(Long), username(String), openTicketCount(Long)}.
     */
    @Query("""
        SELECT u.id, u.username, COUNT(t.id)
        FROM com.att.tdp.issueflow.user.User u
        LEFT JOIN Ticket t
               ON t.assignee = u
              AND t.project.id = :projectId
              AND t.status <> com.att.tdp.issueflow.ticket.TicketStatus.DONE
        WHERE u.role = com.att.tdp.issueflow.user.UserRole.DEVELOPER
        GROUP BY u.id, u.username, u.createdAt
        ORDER BY COUNT(t.id) ASC, u.createdAt ASC
        """)
    List<Object[]> findWorkloadByProject(@Param("projectId") Long projectId);
}