package com.att.tdp.issueflow.audit;

/**
 * Catalog of audited actions. Kept as an enum so the audit table values
 * are constrained and refactor-safe. New actions are added here.
 */
public enum AuditAction {
    // User
    USER_CREATE, USER_UPDATE, USER_DELETE,

    // Project
    PROJECT_CREATE, PROJECT_UPDATE, PROJECT_DELETE, PROJECT_RESTORE,

    // Ticket
    TICKET_CREATE, TICKET_UPDATE, TICKET_DELETE, TICKET_RESTORE,
    TICKET_STATUS_CHANGE, TICKET_PRIORITY_CHANGE, TICKET_ASSIGN,
    AUTO_ASSIGN, AUTO_ESCALATE,

    // Comment
    COMMENT_CREATE, COMMENT_UPDATE, COMMENT_DELETE,

    // Dependency
    DEPENDENCY_ADD, DEPENDENCY_REMOVE,

    // Attachment
    ATTACHMENT_UPLOAD, ATTACHMENT_DELETE,

    // CSV
    TICKET_IMPORT,

    // Auth
    LOGIN, LOGOUT
}