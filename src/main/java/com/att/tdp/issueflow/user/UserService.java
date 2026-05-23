package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.user.dto.CreateUserRequest;
import com.att.tdp.issueflow.user.dto.UpdateUserRequest;
import com.att.tdp.issueflow.user.dto.UserResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.List;

/**
 * Business operations for {@link User}.
 *
 * Read-only by default; mutating methods explicitly declare @Transactional
 * to opt into a read-write transaction.
 *
 * Audit-log writes are intentionally absent at this phase — AuditService
 * arrives in Phase 9, at which point this service is updated to record
 * USER_CREATE, USER_UPDATE, and USER_DELETE events.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public List<UserResponse> findAll() {
        return userRepository.findAll().stream()
                .map(UserResponse::from)
                .toList();
    }

    public UserResponse findById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("User", id));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse create(CreateUserRequest req) {
        // Pre-check uniqueness for clearer error messages.
        // The DB unique constraints are the source of truth — race conditions
        // between this check and the insert will surface as
        // DataIntegrityViolationException, which the global handler maps to 409.
        if (userRepository.existsByUsernameIgnoreCase(req.username())) {
            throw new ConflictException("Username already exists: " + req.username());
        }
        if (userRepository.existsByEmail(req.email())) {
            throw new ConflictException("Email already exists: " + req.email());
        }

        User user = new User(
                req.username(),
                req.email(),
                passwordEncoder.encode(req.password()),
                req.fullName(),
                req.role()
        );
        User saved = userRepository.save(user);

        auditService.record(
                AuditAction.USER_CREATE,
                AuditEntityType.USER,
                saved.getId(),
                Map.of("username", saved.getUsername()));
        
        return UserResponse.from(saved);
    }

    @Transactional
    public void update(Long id, UpdateUserRequest req) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("User", id));

        // Both fields optional per contract; entity method ignores nulls.
        user.updateProfile(req.fullName(), req.role());

        // No explicit save() needed — managed entity in a @Transactional method
        // is automatically flushed on commit (Hibernate dirty checking).

        auditService.record(
                AuditAction.USER_UPDATE,
                AuditEntityType.USER,
                id,
                null);    
    }

    @Transactional
    public void delete(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("User", id));

        try {
            userRepository.delete(user);
            userRepository.flush();  // force the DELETE now so FK violation surfaces inside the try
        } catch (DataIntegrityViolationException ex) {
            // Triggered when the user is referenced by projects, comments, or
            // attachments (ON DELETE RESTRICT). Translate to a clearer 409.
            throw new ConflictException(
                "User cannot be deleted because they own projects, comments, or attachments.");
        }

        auditService.record(
                AuditAction.USER_DELETE,
                AuditEntityType.USER,
                id,
                null);
    }
}