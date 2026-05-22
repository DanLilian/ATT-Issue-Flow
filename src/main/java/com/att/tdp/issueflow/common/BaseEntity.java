package com.att.tdp.issueflow.common;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;

/**
 * Common identity and auditing fields for all persistent entities.
 *
 * Subclasses inherit {@code id}, {@code createdAt}, and {@code updatedAt}.
 * {@link AuditingEntityListener} populates the timestamps on insert/update
 * provided {@code @EnableJpaAuditing} is active (configured in JpaConfig).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // equals/hashCode based on id only when persisted.
    // Two transient entities are never equal. Standard JPA pattern.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseEntity that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        // Constant hash for transient entities so they remain
        // findable in collections before being persisted.
        return getClass().hashCode();
    }
}