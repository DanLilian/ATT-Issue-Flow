package com.att.tdp.issueflow.common;

import java.util.List;

/**
 * Generic paginated response matching the README's {data, total, page}
 * contract. Used wherever we expose a paginated list to clients.
 *
 * (MentionsPageResponse from Phase 8 predates this and could be migrated
 * later; leaving it alone for now to avoid touching Phase 8's tests.)
 */
public record PageResponse<T>(List<T> data, long total, int page) {}