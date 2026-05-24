package com.att.tdp.issueflow.auth;

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
 * Proves the full @PreAuthorize / JWT-role-claim / SecurityFilterChain
 * stack actually enforces ADMIN-only on the right endpoints, and that
 * the wiring of 'ROLE_' + role.name() in AppUserPrincipal cooperates
 * with hasRole('ADMIN') in the controllers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAuthorizationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String RAW_PASSWORD = "password123";
    private String adminToken;
    private String devToken;
    private Long projectId;

    @BeforeEach
    void seed() throws Exception {
        User admin = userRepository.save(new User(
                "admin", "admin@x.com",
                passwordEncoder.encode(RAW_PASSWORD),
                "Admin", UserRole.ADMIN));
        userRepository.save(new User(
                "dev", "dev@x.com",
                passwordEncoder.encode(RAW_PASSWORD),
                "Dev", UserRole.DEVELOPER));

        Project p = projectRepository.save(new Project("Proj", "d", admin));
        projectId = p.getId();

        adminToken = login("admin");
        devToken = login("dev");
    }

    // ─── /projects/deleted ──────────────────────────────────────────────

    @Test
    void getDeletedProjects_developerForbidden_adminAllowed() throws Exception {
        mockMvc.perform(get("/projects/deleted")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/projects/deleted")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ─── /tickets/deleted ───────────────────────────────────────────────

    @Test
    void getDeletedTickets_developerForbidden_adminAllowed() throws Exception {
        mockMvc.perform(get("/tickets/deleted")
                        .param("projectId", projectId.toString())
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/tickets/deleted")
                        .param("projectId", projectId.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ─── /audit-logs ────────────────────────────────────────────────────

    @Test
    void getAuditLogs_developerForbidden_adminAllowed() throws Exception {
        mockMvc.perform(get("/audit-logs")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/audit-logs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ─── project restore ────────────────────────────────────────────────

    @Test
    void restoreProject_developerForbidden_adminAllowed() throws Exception {
        // Soft-delete first so there's something to restore.
        mockMvc.perform(delete("/projects/" + projectId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/projects/" + projectId + "/restore")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/projects/" + projectId + "/restore")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
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