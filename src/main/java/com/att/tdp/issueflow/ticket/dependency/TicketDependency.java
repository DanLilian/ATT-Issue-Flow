package com.att.tdp.issueflow.ticket.dependency;

import com.att.tdp.issueflow.ticket.Ticket;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "ticket_dependencies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class TicketDependency {

    @EmbeddedId
    private TicketDependencyId id;

    /**
     * The ticket that IS blocked. @MapsId reuses the embedded id's
     * ticketId column as the FK — no extra column, no double-mapping.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("ticketId")
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    /** The ticket that DOES the blocking. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("blockerTicketId")
    @JoinColumn(name = "blocker_ticket_id")
    private Ticket blocker;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TicketDependency(Ticket ticket, Ticket blocker) {
        this.ticket = ticket;
        this.blocker = blocker;
        this.id = new TicketDependencyId(ticket.getId(), blocker.getId());
    }
}