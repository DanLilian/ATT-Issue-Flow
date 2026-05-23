package com.att.tdp.issueflow.user.dto;

import com.att.tdp.issueflow.user.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /users.
 *
 * The README contract omits {@code password}; we add it because
 * POST /auth/login requires the user to have one. This is the only
 * defensible place to set it (alternatives — auto-generated password,
 * separate set-password endpoint — add complexity without value at this scale).
 * The deviation is documented in prompts.md.
 */
public record CreateUserRequest(

        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(regexp = "^[A-Za-z0-9_.-]+$",
                 message = "username may contain letters, digits, dot, dash, underscore only")
        String username,

        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(max = 200)
        String fullName,

        @NotNull
        UserRole role,

        @NotBlank
        @Size(min = 8, max = 100,
              message = "password must be between 8 and 100 characters")
        String password
) {}