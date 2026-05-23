package com.att.tdp.issueflow.common.error;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import java.util.List;

/**
 * Centralized exception → HTTP response mapping for the whole API.
 *
 * Strategy:
 *   - 4xx (client error) logged at WARN with a one-liner.
 *   - 5xx (server error) logged at ERROR with full stack trace.
 *   - Body shape is always {@link ApiError}.
 *   - Domain exceptions ({@link NotFoundException}, {@link ConflictException},
 *     etc.) carry only a message; the HTTP status is chosen here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ─── Domain exceptions ──────────────────────────────────────────────

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest req) {
        return clientError(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest req) {
        return clientError(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(ForbiddenActionException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenActionException ex, HttpServletRequest req) {
        return clientError(HttpStatus.FORBIDDEN, ex.getMessage(), req);
    }

    /**
     * Spring Security 6.x throws AuthorizationDeniedException from
     * @PreAuthorize / @PostAuthorize when the authorization check fails.
     * Maps to 403 with our ApiError shape — same outcome as
     * ForbiddenActionException, different source.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiError> handleAuthorizationDenied(AuthorizationDeniedException ex,
                                                            HttpServletRequest req) {
        return clientError(HttpStatus.FORBIDDEN, "Access denied", req);
    }
    
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex, HttpServletRequest req) {
        return clientError(HttpStatus.UNAUTHORIZED, ex.getMessage(), req);
        }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> handleValidation(ValidationException ex, HttpServletRequest req) {
        return clientError(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    // ─── Bean Validation (DTO @Valid failures) ──────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex,
                                                         HttpServletRequest req) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(),
                        fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid value"))
                .toList();
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, "Validation failed",
                                    req.getRequestURI(), fieldErrors);
        log.warn("Validation failed at {}: {}", req.getRequestURI(), fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** @Validated on @RequestParam / @PathVariable (constraint on the method param). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                              HttpServletRequest req) {
        List<ApiError.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(cv -> new ApiError.FieldError(cv.getPropertyPath().toString(), cv.getMessage()))
                .toList();
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, "Validation failed",
                                    req.getRequestURI(), fieldErrors);
        log.warn("Constraint violation at {}: {}", req.getRequestURI(), fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ─── Concurrency / persistence ──────────────────────────────────────

    /**
     * Triggered when @Version detects a stale update. Two users tried to
     * modify the same ticket or comment; the slower commit loses. Per spec,
     * this maps to 409 Conflict with a clear message.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                         HttpServletRequest req) {
        return clientError(HttpStatus.CONFLICT,
                "Resource was modified by another user. Reload and retry.", req);
    }

    /**
     * DB-level constraint violation (unique key, FK, check). We do not
     * surface the underlying SQL state to clients — that can leak schema
     * details. Generic 409 with a vague message.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex,
                                                        HttpServletRequest req) {
        log.warn("Data integrity violation at {}: {}", req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return clientError(HttpStatus.CONFLICT,
                "Request violates a data integrity constraint (duplicate or referential).", req);
    }

    // ─── Request parsing / type problems ────────────────────────────────

    /**
     * Malformed JSON body or wrong type for an enum / number field.
     * 400 with a hint about what went wrong, without leaking internal types.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                         HttpServletRequest req) {
        String message = "Malformed request body";
        if (ex.getCause() instanceof InvalidFormatException ife) {
            String field = ife.getPath().isEmpty() ? "?"
                    : ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            message = "Invalid value for field '%s'".formatted(field);
        }
        return clientError(HttpStatus.BAD_REQUEST, message, req);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex,
                                                       HttpServletRequest req) {
        return clientError(HttpStatus.BAD_REQUEST,
                "Missing required parameter: " + ex.getParameterName(), req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest req) {
        return clientError(HttpStatus.BAD_REQUEST,
                "Invalid value for parameter: " + ex.getName(), req);
    }

    // ─── File upload ────────────────────────────────────────────────────

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUpload(MaxUploadSizeExceededException ex,
                                                    HttpServletRequest req) {
        return clientError(HttpStatus.PAYLOAD_TOO_LARGE,
                "Uploaded file exceeds the maximum allowed size of 10 MB.", req);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex,
                                                      HttpServletRequest req) {
        return clientError(HttpStatus.BAD_REQUEST,
                "Missing required multipart part: " + ex.getRequestPartName(), req);
    }

    // ─── Routing / unknown paths ────────────────────────────────────────

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex,
                                                     HttpServletRequest req) {
        return clientError(HttpStatus.NOT_FOUND,
                "No endpoint mapped for " + req.getMethod() + " " + req.getRequestURI(), req);
    }

    // ─── Catch-all ──────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception at {} {}: {}", req.getMethod(), req.getRequestURI(),
                  ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                  "An unexpected error occurred.", req.getRequestURI()));
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private ResponseEntity<ApiError> clientError(HttpStatus status, String message,
                                                 HttpServletRequest req) {
        log.warn("{} at {} {}: {}", status.value(), req.getMethod(), req.getRequestURI(), message);
        return ResponseEntity.status(status)
                .body(ApiError.of(status, message, req.getRequestURI()));
    }
}