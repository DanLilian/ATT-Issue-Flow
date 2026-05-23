package com.att.tdp.issueflow.ticket.dependency.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body for POST /tickets/{ticketId}/dependencies.
 * Per the README contract, field is named "blockedBy" — the id of the
 * ticket that blocks the one in the URL path.
 */
public record AddDependencyRequest(

        @NotNull
        @Positive
        Long blockedBy
) {}