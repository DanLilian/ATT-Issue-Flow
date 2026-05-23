package com.att.tdp.issueflow.ticket.dto;

import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;

import java.time.Instant;

/**
 * Response body matching the README contract:
 * { id, title, description, status, priority, type, projectId,
 *   assigneeId, dueDate, isOverdue }
 *
 * project and assignee are projected via the lazy proxy's id shortcut
 * (no DB hit; the id is already on the proxy). assignee may be null.
 */
public record TicketResponse(
        Long id,
        String title,
        String description,
        TicketStatus status,
        TicketPriority priority,
        TicketType type,
        Long projectId,
        Long assigneeId,
        Instant dueDate,
        boolean isOverdue
) {
    public static TicketResponse from(Ticket t) {
        return new TicketResponse(
                t.getId(),
                t.getTitle(),
                t.getDescription(),
                t.getStatus(),
                t.getPriority(),
                t.getType(),
                t.getProject().getId(),
                t.getAssignee() != null ? t.getAssignee().getId() : null,
                t.getDueDate(),
                t.isOverdue()
        );
    }
}