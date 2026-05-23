package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.user.dto.CreateUserRequest;
import com.att.tdp.issueflow.user.dto.UpdateUserRequest;
import com.att.tdp.issueflow.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for /users.
 *
 * Endpoint shapes follow the README API contract exactly, including
 * two contract quirks:
 *   1. POST /users returns 200 OK (not the conventional 201 Created).
 *   2. Updates use POST /users/update/{userId} (not PATCH /users/{userId}).
 * These are intentional and not to be "fixed" without changing the contract.
 *
 * No authorization here yet — that lands in Phase 5 when Spring Security
 * is wired and the security config gates endpoints by role.
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserResponse> getAll() {
        return userService.findAll();
    }

    @GetMapping("/{userId}")
    public UserResponse getById(@PathVariable Long userId) {
        return userService.findById(userId);
    }

    @PostMapping
    public UserResponse create(@Valid @RequestBody CreateUserRequest req) {
        return userService.create(req);
        // Returns 200 (not 201) per README contract.
    }

    @PostMapping("/update/{userId}")
    public ResponseEntity<Void> update(@PathVariable Long userId,
                                       @Valid @RequestBody UpdateUserRequest req) {
        userService.update(userId, req);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> delete(@PathVariable Long userId) {
        userService.delete(userId);
        return ResponseEntity.ok().build();
    }
}