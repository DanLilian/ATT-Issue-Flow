package com.att.tdp.issueflow.project.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for PATCH /projects/{id}. Both fields optional; the
 * service applies only what's provided. @Size on a null value is treated
 * as valid by Bean Validation, so partial updates work naturally.
 *
 * Empty-string name is explicitly rejected via @Size(min = 1) — clients
 * cannot break the entity invariant via PATCH.
 */
public record UpdateProjectRequest(

        @Size(min = 1, max = 200)
        String name,

        @Size(max = 2000)
        String description
) {}