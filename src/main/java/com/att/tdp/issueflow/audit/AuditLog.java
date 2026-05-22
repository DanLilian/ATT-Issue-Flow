package com.att.tdp.issueflow.audit;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private AuditEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditActor actor;

    /** Null when actor = SYSTEM (e.g. AUTO_ASSIGN, AUTO_ESCALATE). */
    @Column(name = "performed_by")
    private Long performedBy;

    /** Free-form JSON payload (old/new values, context). Serialized by AuditService. */
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AuditLog(AuditAction action, AuditEntityType entityType, Long entityId,
                    AuditActor actor, Long performedBy, String metadataJson) {
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.actor = actor;
        this.performedBy = performedBy;
        this.metadataJson = metadataJson;
    }
}