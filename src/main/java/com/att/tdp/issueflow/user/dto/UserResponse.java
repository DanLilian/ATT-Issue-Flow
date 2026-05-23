package com.att.tdp.issueflow.user.dto;

import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRole;

/**
 * Response body for user-related endpoints. Matches the README contract
 * exactly: {id, username, email, fullName, role}. Password hash is never
 * included.
 */
public record UserResponse(
        Long id,
        String username,
        String email,
        String fullName,
        UserRole role
) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getEmail(),
                                u.getFullName(), u.getRole());
    }
}