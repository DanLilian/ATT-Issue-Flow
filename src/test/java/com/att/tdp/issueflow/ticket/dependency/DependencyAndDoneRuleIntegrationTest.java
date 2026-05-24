package com.att.tdp.issueflow.ticket.dependency;

import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
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
 * Verifies the cross-feature interaction between Phase 10's dependency
 * rules and Phase 7's DONE-transition check, end to end through HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DependencyAndDoneRuleIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String RAW_PASSWORD = "password123";
    private Long projectId;
    private String token;

    @BeforeEach
    void seed() throws Exception {
        User admin = userRepository.save(new User(
                "admin", "admin@x.com",
                passwordEncoder.encode(RAW_PASSWORD),
                "Admin", UserRole.ADMIN));
        Project p = projectRepository.save(new Project("Proj", "d", admin));
        projectId = p.getId();
        token = login("admin");
    }

    @Test
    void cannotMoveToDone_whileBlockerUnresolved_thenCanAfterBlockerDone() throws Exception {
        // Create blocked + blocker tickets.
        Long blockedId = createTicket("Blocked");
        Long blockerId = createTicket("Blocker");

        // Add dependency: blocked is blocked by blocker.
        mockMvc.perform(post("/tickets/" + blockedId + "/dependencies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "blockedBy": %d }
                            """.formatted(blockerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockerId").value(blockerId));

        // Walk blocked through TODO -> IN_PROGRESS -> IN_REVIEW (legal).
        patchStatus(blockedId, "IN_PROGRESS").andExpect(status().isOk());
        patchStatus(blockedId, "IN_REVIEW").andExpect(status().isOk());

        // Attempt blocked -> DONE while blocker is still TODO -> 409.
        patchStatus(blockedId, "DONE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("unresolved blockers")));

        // Walk blocker to DONE.
        patchStatus(blockerId, "IN_PROGRESS").andExpect(status().isOk());
        patchStatus(blockerId, "IN_REVIEW").andExpect(status().isOk());
        patchStatus(blockerId, "DONE").andExpect(status().isOk());

        // Now blocked -> DONE succeeds.
        patchStatus(blockedId, "DONE").andExpect(status().isOk());
    }

    @Test
    void cycle_rejectedWith409() throws Exception {
        Long a = createTicket("A");
        Long b = createTicket("B");

        // A blocked by B
        mockMvc.perform(post("/tickets/" + a + "/dependencies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "blockedBy": %d }
                            """.formatted(b)))
                .andExpect(status().isOk());

        // Now B blocked by A -> cycle -> 409
        mockMvc.perform(post("/tickets/" + b + "/dependencies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "blockedBy": %d }
                            """.formatted(a)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("cycle")));
    }

    @Test
    void selfBlock_rejectedWith409() throws Exception {
        Long a = createTicket("A");

        mockMvc.perform(post("/tickets/" + a + "/dependencies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "blockedBy": %d }
                            """.formatted(a)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("itself")));
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private Long createTicket(String title) throws Exception {
        String body = """
            {
              "title": "%s",
              "description": "d",
              "status": "TODO",
              "priority": "LOW",
              "type": "BUG",
              "projectId": %d
            }
            """.formatted(title, projectId);

        MvcResult r = mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resp = json.readTree(r.getResponse().getContentAsString());
        return resp.get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions patchStatus(
            Long ticketId, String status) throws Exception {
        return mockMvc.perform(patch("/tickets/" + ticketId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "status": "%s" }
                    """.formatted(status)));
    }

    private String login(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "username": "%s", "password": "%s" }
                            """.formatted(username, RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}