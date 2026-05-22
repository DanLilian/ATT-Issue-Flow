package com.att.tdp.issueflow.ticket.dependency;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Composite primary key for {@link TicketDependency}.
 * The pair (ticketId, blockerTicketId) uniquely identifies a blocking relation.
 *
 * Must be Serializable and override equals/hashCode — JPA spec requirement
 * for composite keys (Hibernate uses them for first-level cache lookups).
 */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class TicketDependencyId implements Serializable {

    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "blocker_ticket_id")
    private Long blockerTicketId;
}