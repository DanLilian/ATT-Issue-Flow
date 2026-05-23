package com.att.tdp.issueflow.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

/**
 * Standard error response shape returned by {@link GlobalExceptionHandler}
 * for every non-2xx response.
 *
 * Fields with null/empty values are omitted from the JSON output so error
 * bodies stay lean. Modelled after RFC 7807 (Problem Details) with a
 * first-class fieldErrors array for Bean Validation failures.
 *
 * Constructed via the static factories — never instantiated directly.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> fieldErrors
) {

    public record FieldError(String field, String message) {}

    public static ApiError of(HttpStatus status, String message, String path) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(),
                            message, path, null);
    }

    public static ApiError of(HttpStatus status, String message, String path,
                              List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(),
                            message, path, fieldErrors);
    }
}