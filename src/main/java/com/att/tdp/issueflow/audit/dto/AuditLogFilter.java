package com.att.tdp.issueflow.audit.dto;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditActor;
import com.att.tdp.issueflow.audit.AuditEntityType;

/**
 * Query-parameter holder for GET /audit-logs filters. All fields optional;
 * combined with AND in the Specification. Using a record + Spring's
 * @ModelAttribute binding keeps the controller method signature short.
 */
public record AuditLogFilter(
        AuditEntityType entityType,
        Long entityId,
        AuditAction action,
        AuditActor actor
) {}