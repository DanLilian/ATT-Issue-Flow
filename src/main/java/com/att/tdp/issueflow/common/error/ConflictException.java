package com.att.tdp.issueflow.common.error;

/**
 * Thrown for business-rule violations that prevent an otherwise valid
 * request from being processed: illegal ticket status transition,
 * attempted update on a DONE ticket, unresolved blockers preventing DONE,
 * duplicate dependency, concurrent-update detection, etc.
 *
 * Mapped to HTTP 409 by the global exception handler.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}