package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.audit.dto.AuditLogFilter;
import com.att.tdp.issueflow.audit.dto.AuditLogResponse;
import com.att.tdp.issueflow.auth.AppUserPrincipal;
import com.att.tdp.issueflow.common.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Append-only audit log.
 *
 * record() runs in its own transaction (REQUIRES_NEW) so an audit failure
 * never rolls back the originating business operation. The trade-off:
 * an audit row may exist for an action that subsequently rolls back. We
 * accept that — audit is observational; losing state changes because of
 * an audit failure would be the cure worse than the disease.
 *
 * Actor resolution reads from SecurityContextHolder. For scheduled jobs
 * (Phase 13) or system-initiated actions, callers pass actor=SYSTEM
 * explicitly via the overload that doesn't consult the context.
 */
@Service
@Transactional(readOnly = true)
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository,
                        ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Record an audit entry for a user-initiated action. The actor is
     * resolved from the security context.
     *
     * Use overload with explicit performedBy for cases where the security
     * context isn't populated yet (e.g., LOGIN — the user is authenticated
     * during the call, but the context isn't set until later in the chain).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action,
                       AuditEntityType entityType,
                       Long entityId,
                       Map<String, Object> metadata) {
        Long performedBy = currentUserId();
        AuditActor actor = (performedBy != null) ? AuditActor.USER : AuditActor.SYSTEM;
        doRecord(action, entityType, entityId, actor, performedBy, metadata);
    }

    /**
     * Record an audit entry with an explicit actor and performedBy. Used by:
     *   - AuthService.login (the principal isn't in the security context yet)
     *   - Future scheduled jobs (Phase 13) where actor=SYSTEM is forced
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action,
                       AuditEntityType entityType,
                       Long entityId,
                       AuditActor actor,
                       Long performedBy,
                       Map<String, Object> metadata) {
        doRecord(action, entityType, entityId, actor, performedBy, metadata);
    }

    private void doRecord(AuditAction action,
                          AuditEntityType entityType,
                          Long entityId,
                          AuditActor actor,
                          Long performedBy,
                          Map<String, Object> metadata) {
        try {
            String metadataJson = (metadata == null || metadata.isEmpty())
                    ? null
                    : objectMapper.writeValueAsString(metadata);

            AuditLog entry = new AuditLog(
                    action, entityType, entityId, actor, performedBy, metadataJson);

            auditLogRepository.save(entry);
        } catch (JsonProcessingException ex) {
            // A serialization failure must not crash the originating
            // operation. Log loudly so it's visible in operations.
            log.error("Failed to serialize audit metadata for {} {}/{}; entry not recorded",
                      action, entityType, entityId, ex);
        } catch (Exception ex) {
            // Belt and suspenders: any other audit failure is swallowed
            // for the same reason. REQUIRES_NEW means this transaction
            // rolls back independently; the caller's transaction is unaffected.
            log.error("Failed to record audit {} {}/{}", action, entityType, entityId, ex);
        }
    }

    /**
     * Paginated, filtered listing. Used by GET /audit-logs.
     * Newest first.
     */
    public PageResponse<AuditLogResponse> find(AuditLogFilter filter, int page, int pageSize) {
        int pageZeroBased = Math.max(0, page - 1);
        Pageable pageable = PageRequest.of(pageZeroBased, pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<AuditLog> spec = AuditLogSpecifications.matching(filter);
        Page<AuditLog> result = auditLogRepository.findAll(spec, pageable);

        return new PageResponse<>(
                result.getContent().stream().map(AuditLogResponse::from).toList(),
                result.getTotalElements(),
                page
        );
    }

    /** Returns the authenticated user's id, or null if no user is authenticated. */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof AppUserPrincipal p) return p.getUserId();
        return null;
    }
}