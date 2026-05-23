package com.att.tdp.issueflow.comment.dto;

import com.att.tdp.issueflow.user.User;

/**
 * Compact view of a mentioned user, embedded in {@link CommentResponse}.
 * Matches the README contract: { id, username, fullName }.
 */
public record MentionedUserView(Long id, String username, String fullName) {
    public static MentionedUserView from(User u) {
        return new MentionedUserView(u.getId(), u.getUsername(), u.getFullName());
    }
}