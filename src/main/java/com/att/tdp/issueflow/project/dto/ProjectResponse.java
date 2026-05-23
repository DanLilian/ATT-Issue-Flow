package com.att.tdp.issueflow.project.dto;

import com.att.tdp.issueflow.project.Project;

/**
 * Response body matching the README contract:
 * { id, name, description, ownerId }
 *
 * Note: ownerId is the FK value, not a nested owner object. The README
 * sample is explicit on this — we project the owner reference rather
 * than serializing the User entity.
 */
public record ProjectResponse(
        Long id,
        String name,
        String description,
        Long ownerId
) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                // Owner is LAZY @ManyToOne — calling getId() on the proxy is
                // safe and does NOT trigger a DB hit. The id is already on
                // the proxy (it's how Hibernate identifies the row).
                project.getOwner().getId()
        );
    }
}