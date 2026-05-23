package com.att.tdp.issueflow.comment.dto;

import java.util.List;

/**
 * Paginated response for GET /users/{userId}/mentions per the README:
 *   { data: [...], total: N, page: K }
 *
 * Deliberately NOT Spring Data's default Page shape (content/totalElements/
 * number/size/...). We adapt at the controller to match the contract.
 */
public record MentionsPageResponse(
        List<CommentResponse> data,
        long total,
        int page
) {}