package com.att.tdp.issueflow.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    // Inherited findById / findAll automatically exclude soft-deleted rows
    // because of @SQLRestriction on the entity.

    boolean existsByName(String name);

    /**
     * Bypasses @SQLRestriction via native SQL.
     * Used by the ADMIN-only GET /projects/deleted endpoint.
     */
    @Query(value = "SELECT * FROM projects WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC",
           nativeQuery = true)
    List<Project> findAllDeleted();

    /**
     * Bypasses @SQLRestriction to fetch a soft-deleted project by id.
     * Used by POST /projects/{id}/restore.
     */
    @Query(value = "SELECT * FROM projects WHERE id = :id AND deleted_at IS NOT NULL",
           nativeQuery = true)
    Optional<Project> findDeletedById(@Param("id") Long id);
}