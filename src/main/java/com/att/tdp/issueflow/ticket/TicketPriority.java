package com.att.tdp.issueflow.ticket;

public enum TicketPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /**
     * Returns the next priority in the escalation ladder, or
     * {@code this} if already at the top (CRITICAL is terminal).
     * Used by the overdue-escalation scheduler.
     */
    public TicketPriority escalate() {
        return switch (this) {
            case LOW      -> MEDIUM;
            case MEDIUM   -> HIGH;
            case HIGH     -> CRITICAL;
            case CRITICAL -> CRITICAL;
        };
    }

    public boolean isMax() {
        return this == CRITICAL;
    }
}