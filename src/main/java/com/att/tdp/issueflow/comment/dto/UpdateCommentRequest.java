package com.att.tdp.issueflow.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for PATCH /tickets/{ticketId}/comments/{commentId}.
 * Content is mandatory — the only field a PATCH can change. Empty
 * content is rejected because the comment row's content is NOT NULL.
 */
public record UpdateCommentRequest(

        @NotBlank
        @Size(min = 1, max = 5000)
        String content
) {}