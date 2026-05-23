package com.att.tdp.issueflow.common.error;

/**
 * Thrown for programmatic validation failures that Bean Validation
 * cannot express cleanly — e.g., "blocker and blocked ticket must belong
 * to the same project" requires a repository lookup, so it lives in the
 * service.
 *
 * Mapped to HTTP 400 by the global exception handler.
 *
 * Use sparingly. Field-level rules (NotBlank, Email, Size, Pattern)
 * belong on the DTO via Bean Validation annotations.
 */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}