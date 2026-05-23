package com.att.tdp.issueflow.ticket.dto;

import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketStatus;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Request body for PATCH /tickets/{id}. All fields optional — null means
 * 'don't change'. This is the standard PATCH convention; the trade-off
 * is that you cannot use PATCH to set assigneeId or dueDate back to null
 * (unassign / clear due date). Neither is in the README's scope.
 *
 * Note: the README lists title, description, status, priority, assigneeId,
 * and dueDate as updatable. type is deliberately NOT updatable — a bug
 * doesn't become a feature.
 */
public record UpdateTicketRequest(

        @Size(min = 1, max = 200)
        String title,

        @Size(max = 5000)
        String description,

        TicketStatus status,

        TicketPriority priority,

        @Positive
        Long assigneeId,

        Instant dueDate
) {}