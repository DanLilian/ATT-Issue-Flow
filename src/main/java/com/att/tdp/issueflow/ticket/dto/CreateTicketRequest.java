package com.att.tdp.issueflow.ticket.dto;

import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Request body for POST /tickets. status, priority, and type are mandatory
 * per the README contract; assigneeId and dueDate are optional.
 *
 * The service validates that projectId and (if provided) assigneeId refer
 * to existing rows, so error messages are clearer than the generic FK
 * violation 409 the database would produce.
 */
public record CreateTicketRequest(

        @NotBlank
        @Size(min = 1, max = 200)
        String title,

        @Size(max = 5000)
        String description,

        @NotNull
        TicketStatus status,

        @NotNull
        TicketPriority priority,

        @NotNull
        TicketType type,

        @NotNull
        @Positive
        Long projectId,

        @Positive
        Long assigneeId,           // optional

        Instant dueDate            // optional
) {}