package com.att.tdp.issueflow.ticket;

import java.util.List;
import java.util.Set;

public enum TicketStatus {
    TODO,
    IN_PROGRESS,
    IN_REVIEW,
    DONE;

    /**
     * Returns the set of statuses that {@code current} may transition to.
     * The lifecycle is forward-only: TODO -> IN_PROGRESS -> IN_REVIEW -> DONE.
     * DONE is terminal; no outgoing transitions allowed.
     */
    public Set<TicketStatus> allowedNext() {
        return switch (this) {
            case TODO        -> Set.of(IN_PROGRESS);
            case IN_PROGRESS -> Set.of(IN_REVIEW);
            case IN_REVIEW   -> Set.of(DONE);
            case DONE        -> Set.of();
        };
    }

    public boolean canTransitionTo(TicketStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return this == DONE;
    }

    /** Ordered list for any UI / display purpose. */
    public static List<TicketStatus> lifecycle() {
        return List.of(TODO, IN_PROGRESS, IN_REVIEW, DONE);
    }
}