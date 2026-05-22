package com.att.tdp.issueflow.ticket.dependency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketDependencyRepository
        extends JpaRepository<TicketDependency, TicketDependencyId> {

    /** Used by GET /tickets/{id}/dependencies and the DONE-blocker check. */
    List<TicketDependency> findByTicketId(Long ticketId);

    /** Existence check for the DONE-transition guard in TicketService. */
    boolean existsByTicketIdAndBlockerStatusNot(
            Long ticketId,
            com.att.tdp.issueflow.ticket.TicketStatus status);

    boolean existsByIdTicketIdAndIdBlockerTicketId(Long ticketId, Long blockerTicketId);
}