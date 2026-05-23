package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import com.att.tdp.issueflow.auth.AuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end integration test for the authentication flow. Real Spring
 * context, real JWT filter chain, real BCrypt verification, real H2.
 *
 * Verifies the whole story: register user (via direct save) -> login ->
 * call a protected endpoint with the token -> /auth/me echoes the user ->
 * logout -> token is rejected on next call.
 *
 * This is the test that proves Phase 5 works as a system. Slice tests
 * cannot do this because they bypass the real filter chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional   // each test rolls back; users created here don't leak
class AuthIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired ObjectMapper json;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String RAW_PASSWORD = "password123";

    @BeforeEach
    void seedUser() {
        User alice = new User(
            "alice", "alice@x.com",
            passwordEncoder.encode(RAW_PASSWORD),
            "Alice", UserRole.DEVELOPER);
        userRepository.save(alice);
    }

    @Test
    void login_returnsToken_andTokenAuthenticatesSubsequentCalls() throws Exception {
        String token = login("alice", RAW_PASSWORD);

        // Use the token to hit /auth/me — proves the filter accepts it.
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"));
    }

    @Test
    void login_rejectsWrongPassword_with401_andGenericMessage() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "username": "alice", "password": "wrong" }
                            """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void login_rejectsUnknownUser_withSameMessage_noEnumeration() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "username": "nosuchuser", "password": "whatever" }
                            """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
        // Same message as wrong-password case: no username enumeration possible.
    }

    @Test
    void protectedEndpoint_rejectsRequestsWithoutToken() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void protectedEndpoint_rejectsTamperedToken() throws Exception {
        String token = login("alice", RAW_PASSWORD);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_revokesToken_andSubsequentCallsAreRejected() throws Exception {
        String token = login("alice", RAW_PASSWORD);

        // Token works before logout.
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Logout.
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Same token now rejected (it's in the deny-list).
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_serviceLayerIsIdempotent() throws Exception {
        String token = login("alice", RAW_PASSWORD);

        // First logout via HTTP — token is now revoked.
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Calling AuthService.logout directly with the now-revoked token is a
        // no-op: the deny-list entry already exists, no duplicate row inserted,
        // no exception thrown. We verify this at the service layer because the
        // HTTP path can no longer be exercised — the JWT filter correctly rejects
        // the revoked token before the controller ever runs.
        authService.logout(token);

        // Sanity: the token is still rejected via HTTP.
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_withoutToken_returns401_perSecurityConfig() throws Exception {
        // Logout is authenticated per SecurityConfig — no token, no entry.
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private String login(String username, String password) throws Exception {
        String body = """
            { "username": "%s", "password": "%s" }
            """.formatted(username, password);

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andReturn();

        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        return response.get("accessToken").asText();
    }
}