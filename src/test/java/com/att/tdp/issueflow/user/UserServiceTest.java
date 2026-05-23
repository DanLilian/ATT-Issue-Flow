package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.common.JpaConfig;
import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.config.PasswordEncoderConfig;
import com.att.tdp.issueflow.user.dto.CreateUserRequest;
import com.att.tdp.issueflow.user.dto.UpdateUserRequest;
import com.att.tdp.issueflow.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice test for UserService backed by a real (H2) repository.
 * Verifies the business behaviour: uniqueness pre-checks, BCrypt hashing,
 * partial updates, and not-found mapping.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UserService.class, PasswordEncoderConfig.class, AuditService.class,
         JpaConfig.class, com.fasterxml.jackson.databind.ObjectMapper.class})
class UserServiceTest {

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private CreateUserRequest validCreateRequest(String username, String email) {
        return new CreateUserRequest(username, email, "Full Name",
                                     UserRole.DEVELOPER, "password123");
    }

    @Test
    void create_persistsUser_andHashesPassword() {
        UserResponse created = userService.create(validCreateRequest("alice", "alice@x.com"));

        assertThat(created.id()).isNotNull();
        assertThat(created.username()).isEqualTo("alice");

        User stored = userRepository.findById(created.id()).orElseThrow();
        // Password is BCrypt-hashed: never equal to the plaintext, always verifiable.
        assertThat(stored.getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", stored.getPasswordHash())).isTrue();
    }

    @Test
    void create_rejectsDuplicateUsername_caseInsensitive() {
        userService.create(validCreateRequest("alice", "alice@x.com"));

        assertThatThrownBy(() ->
                userService.create(validCreateRequest("ALICE", "different@x.com")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Username already exists");
    }

    @Test
    void create_rejectsDuplicateEmail() {
        userService.create(validCreateRequest("alice", "alice@x.com"));

        assertThatThrownBy(() ->
                userService.create(validCreateRequest("bob", "alice@x.com")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Email already exists");
    }

    @Test
    void findById_throwsNotFound_whenMissing() {
        assertThatThrownBy(() -> userService.findById(99_999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User with id 99999 not found");
    }

    @Test
    void findAll_returnsEverything() {
        userService.create(validCreateRequest("alice", "alice@x.com"));
        userService.create(validCreateRequest("bob",   "bob@x.com"));

        List<UserResponse> all = userService.findAll();

        assertThat(all).hasSize(2)
                .extracting(UserResponse::username)
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void update_appliesOnlyProvidedFields() {
        UserResponse created = userService.create(validCreateRequest("alice", "alice@x.com"));

        // Only fullName provided; role left null and should NOT change.
        userService.update(created.id(), new UpdateUserRequest("Alice Smith", null));

        User stored = userRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getFullName()).isEqualTo("Alice Smith");
        assertThat(stored.getRole()).isEqualTo(UserRole.DEVELOPER); // unchanged
    }

    @Test
    void update_changesRoleAlone() {
        UserResponse created = userService.create(validCreateRequest("alice", "alice@x.com"));

        userService.update(created.id(), new UpdateUserRequest(null, UserRole.ADMIN));

        User stored = userRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getFullName()).isEqualTo("Full Name"); // unchanged
        assertThat(stored.getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void update_throwsNotFound_whenUserMissing() {
        assertThatThrownBy(() ->
                userService.update(99_999L, new UpdateUserRequest("X", UserRole.ADMIN)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void delete_removesUser_whenNoReferences() {
        UserResponse created = userService.create(validCreateRequest("alice", "alice@x.com"));

        userService.delete(created.id());

        assertThat(userRepository.findById(created.id())).isEmpty();
    }

    @Test
    void delete_throwsNotFound_whenUserMissing() {
        assertThatThrownBy(() -> userService.delete(99_999L))
                .isInstanceOf(NotFoundException.class);
    }

    // Note: delete-with-references (FK RESTRICT -> ConflictException) is best
    // tested as an integration test in Phase 6 once Project exists. Adding
    // a fake referencing entity here would duplicate Phase 6's setup.
}