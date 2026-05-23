package com.att.tdp.issueflow.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /projects per the README contract.
 * ownerId must refer to an existing user — verified in the service
 * because Bean Validation cannot do a repository lookup.
 */
public record CreateProjectRequest(

        @NotBlank
        @Size(min = 1, max = 200)
        String name,

        @Size(max = 2000)
        String description,

        @NotNull
        @Positive
        Long ownerId
) {}