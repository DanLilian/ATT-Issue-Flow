package com.att.tdp.issueflow.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /tickets/{ticketId}/comments per the README.
 * authorId is in the body (per the contract), not derived from the JWT —
 * deliberate choice to match the spec exactly. The service validates that
 * the user exists.
 */
public record CreateCommentRequest(

        @NotNull
        @Positive
        Long authorId,

        @NotBlank
        @Size(min = 1, max = 5000)
        String content
) {}