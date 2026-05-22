package com.att.tdp.issueflow.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditLogRepository extends
        JpaRepository<AuditLog, Long>,
        JpaSpecificationExecutor<AuditLog> {

    /**
     * Specification factory for the filter endpoint.
     * GET /audit-logs accepts optional entityType, entityId, action, actor.
     *
     * Specifications compose with AND; we'll build the spec in the service
     * by chaining the non-null filters. This is cleaner than four derived
     * query methods or a JPQL with optional clauses.
     */
    static Specification<AuditLog> filterBy(AuditEntityType entityType, Long entityId,
                                            AuditAction action, AuditActor actor) {
        return (root, query, cb) -> {
            var preds = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (entityType != null) preds.add(cb.equal(root.get("entityType"), entityType));
            if (entityId   != null) preds.add(cb.equal(root.get("entityId"),   entityId));
            if (action     != null) preds.add(cb.equal(root.get("action"),     action));
            if (actor      != null) preds.add(cb.equal(root.get("actor"),      actor));
            query.orderBy(cb.desc(root.get("createdAt")));
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    @Override
    Page<AuditLog> findAll(Specification<AuditLog> spec, Pageable pageable);
}