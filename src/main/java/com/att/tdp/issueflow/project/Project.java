package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.common.BaseEntity;
import com.att.tdp.issueflow.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// Any HQL/JPQL/find against this entity automatically appends this filter.
// Deleted projects are invisible unless we use a native query (admin endpoints).
@SQLRestriction("deleted_at IS NULL")
// Override the default DELETE statement so repository.delete(project) is a soft delete.
@SQLDelete(sql = "UPDATE projects SET deleted_at = NOW() WHERE id = ?")
public class Project extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    /** Owner is mandatory per the spec; user is loaded lazily and read via getId(). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public Project(String name, String description, User owner) {
        this.name = name;
        this.description = description;
        this.owner = owner;
    }

    public void updateDetails(String name, String description) {
        if (name != null) this.name = name;
        if (description != null) this.description = description;
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
    }

    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}