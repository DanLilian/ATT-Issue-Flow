package com.att.tdp.issueflow.auth.dto;

import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRole;

/**
 * Response body for GET /auth/me. Same shape as UserResponse —
 * the answer to "who am I" is the caller's user profile.
 */
public record MeResponse(
        Long id,
        String username,
        String email,
        String fullName,
        UserRole role
) {
    public static MeResponse from(User user) {
        return new MeResponse(user.getId(), user.getUsername(), user.getEmail(),
                              user.getFullName(), user.getRole());
    }
}