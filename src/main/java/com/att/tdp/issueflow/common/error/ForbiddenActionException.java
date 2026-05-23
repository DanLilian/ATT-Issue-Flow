package com.att.tdp.issueflow.common.error;

/**
 * Thrown when the caller is authenticated but not permitted to perform
 * the requested action — e.g., a DEVELOPER attempting to restore a
 * soft-deleted ticket.
 *
 * Mapped to HTTP 403 by the global exception handler.
 *
 * Note: pre-auth failures (missing/invalid token) come from Spring Security
 * and map to 401 via the security configuration in Phase 5, not here.
 */
public class ForbiddenActionException extends RuntimeException {
    public ForbiddenActionException(String message) {
        super(message);
    }
}