package com.att.tdp.issueflow.comment.dto;

import com.att.tdp.issueflow.comment.Comment;

import java.util.List;

/**
 * Response body for comment endpoints, matching the README contract:
 *   { id, ticketId, authorId, content, mentionedUsers: [{id, username, fullName}] }
 *
 * mentionedUsers is always present (empty list if no mentions) — easier
 * for clients than an absent / null field.
 */
public record CommentResponse(
        Long id,
        Long ticketId,
        Long authorId,
        String content,
        List<MentionedUserView> mentionedUsers
) {
    public static CommentResponse from(Comment c) {
        List<MentionedUserView> mentions = c.getMentions().stream()
                .map(m -> MentionedUserView.from(m.getMentionedUser()))
                .toList();
        return new CommentResponse(
                c.getId(),
                c.getTicket().getId(),    // lazy id-shortcut, no DB hit
                c.getAuthor().getId(),    // same
                c.getContent(),
                mentions
        );
    }
}