package com.att.tdp.issueflow.user.dto;

import com.att.tdp.issueflow.user.UserRole;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /users/update/{userId}.
 *
 * Per the README contract only fullName and role are mutable.
 * Both fields are optional — the service ignores nulls and applies
 * only what's provided. Bean Validation runs against the fields that
 * are present (Size on a null value is treated as valid).
 */
public record UpdateUserRequest(

        @Size(min = 1, max = 200)
        String fullName,

        UserRole role
) {}