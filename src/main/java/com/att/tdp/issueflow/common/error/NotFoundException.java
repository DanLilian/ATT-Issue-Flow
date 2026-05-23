package com.att.tdp.issueflow.common.error;

/**
 * Thrown when a referenced entity does not exist (or is soft-deleted and
 * therefore invisible to the standard query path). Mapped to HTTP 404 by
 * the global exception handler.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }

    /** Convenience for the very common "X with id Y not found" message. */
    public static NotFoundException of(String entityName, Object id) {
        return new NotFoundException("%s with id %s not found".formatted(entityName, id));
    }
}