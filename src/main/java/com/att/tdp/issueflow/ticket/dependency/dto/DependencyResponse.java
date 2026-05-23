package com.att.tdp.issueflow.ticket.dependency.dto;

import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.dependency.TicketDependency;

/**
 * Response item for GET /tickets/{ticketId}/dependencies.
 * Projects the blocker's identity, title, and status so the UI can
 * render the dependency without a second round trip per blocker.
 */
public record DependencyResponse(
        Long blockerId,
        String blockerTitle,
        TicketStatus blockerStatus
) {
    public static DependencyResponse from(TicketDependency dep) {
        Ticket blocker = dep.getBlocker();
        return new DependencyResponse(
                blocker.getId(),
                blocker.getTitle(),
                blocker.getStatus()
        );
    }
}