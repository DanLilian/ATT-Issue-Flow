package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.GlobalExceptionHandler;
import com.att.tdp.issueflow.config.SecurityConfig;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.user.dto.UpdateUserRequest;
import com.att.tdp.issueflow.user.dto.UserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for UserController. Service is mocked so the test focuses on
 * HTTP shape: status codes, request validation, JSON serialization, error
 * mapping via the shared GlobalExceptionHandler.
 */
@WebMvcTest(UserController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class UserControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @MockitoBean UserService userService;

    @Test
    void getAllUsers_returnsList() throws Exception {
        when(userService.findAll()).thenReturn(List.of(
                new UserResponse(1L, "alice", "alice@x.com", "Alice", UserRole.DEVELOPER),
                new UserResponse(2L, "bob",   "bob@x.com",   "Bob",   UserRole.ADMIN)));

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[1].role").value("ADMIN"));
    }

    @Test
    void getUser_returnsUser() throws Exception {
        when(userService.findById(1L)).thenReturn(
                new UserResponse(1L, "alice", "alice@x.com", "Alice", UserRole.DEVELOPER));

        mockMvc.perform(get("/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void getUser_returns404_whenMissing() throws Exception {
        when(userService.findById(99L)).thenThrow(NotFoundException.of("User", 99L));

        mockMvc.perform(get("/users/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("User with id 99 not found"));
    }

    @Test
    void createUser_returns200_andUserResponse_perReadmeContract() throws Exception {
        when(userService.create(any())).thenReturn(
                new UserResponse(1L, "alice", "alice@x.com", "Alice", UserRole.DEVELOPER));

        String body = """
            {
              "username": "alice",
              "email": "alice@x.com",
              "fullName": "Alice",
              "role": "DEVELOPER",
              "password": "password123"
            }
            """;

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())            // 200, NOT 201 — README contract
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void createUser_returns400_whenEmailMalformed() throws Exception {
        String body = """
            {
              "username": "alice",
              "email": "not-an-email",
              "fullName": "Alice",
              "role": "DEVELOPER",
              "password": "password123"
            }
            """;

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='email')]").exists());
    }

    @Test
    void createUser_returns400_whenPasswordTooShort() throws Exception {
        String body = """
            {
              "username": "alice",
              "email": "alice@x.com",
              "fullName": "Alice",
              "role": "DEVELOPER",
              "password": "short"
            }
            """;

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='password')]").exists());
    }

    @Test
    void createUser_returns400_whenRoleInvalid() throws Exception {
        // Jackson rejects unknown enum values with InvalidFormatException,
        // mapped by GlobalExceptionHandler to 400 with field-aware message.
        String body = """
            {
              "username": "alice",
              "email": "alice@x.com",
              "fullName": "Alice",
              "role": "WIZARD",
              "password": "password123"
            }
            """;

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("role")));
    }

    @Test
    void createUser_returns409_whenServiceReportsConflict() throws Exception {
        when(userService.create(any()))
                .thenThrow(new ConflictException("Username already exists: alice"));

        String body = """
            {
              "username": "alice",
              "email": "alice@x.com",
              "fullName": "Alice",
              "role": "DEVELOPER",
              "password": "password123"
            }
            """;

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already exists: alice"));
    }

    @Test
    void updateUser_postsToUnusualPath_perReadmeContract() throws Exception {
        // README endpoint is POST /users/update/{id}, NOT PATCH /users/{id}.
        String body = """
            { "fullName": "Alice Smith", "role": "ADMIN" }
            """;

        mockMvc.perform(post("/users/update/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(userService).update(eq(1L), any(UpdateUserRequest.class));
    }

    @Test
    void deleteUser_returns200_withEmptyBody() throws Exception {
        mockMvc.perform(delete("/users/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(userService).delete(1L);
    }
}