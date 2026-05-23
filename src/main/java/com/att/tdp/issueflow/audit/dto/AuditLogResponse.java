package com.att.tdp.issueflow.audit.dto;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditActor;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditLog;

import java.time.Instant;

/**
 * Response body for GET /audit-logs entries. Flat shape matching the
 * underlying entity; metadata is left as a raw JSON string so callers
 * can parse on their own terms (action-specific).
 */
public record AuditLogResponse(
        Long id,
        AuditAction action,
        AuditEntityType entityType,
        Long entityId,
        AuditActor actor,
        Long performedBy,
        String metadata,
        Instant createdAt
) {
    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getActor(),
                log.getPerformedBy(),
                log.getMetadataJson(),
                log.getCreatedAt()
        );
    }
}