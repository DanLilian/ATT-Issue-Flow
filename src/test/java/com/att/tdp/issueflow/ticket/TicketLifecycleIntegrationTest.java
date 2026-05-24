package com.att.tdp.issueflow.ticket;

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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end ticket lifecycle through the HTTP layer with full Spring
 * Security filter chain. Verifies that the slice-tested rules (forward
 * status transitions, DONE locking, soft delete, admin restore) all
 * cooperate when invoked through MockMvc with a real JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketLifecycleIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @PersistenceContext EntityManager entityManager;
    private static final String RAW_PASSWORD = "password123";
    private Long projectId;
    private String devToken;
    private String adminToken;

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

    @Test
    void fullLifecycle_create_transition_doneLocks_softDelete_restore() throws Exception {
        // CREATE
        Long ticketId = createTicket();

        // TODO -> IN_PROGRESS
        patchStatus(ticketId, "IN_PROGRESS", devToken).andExpect(status().isOk());

        // IN_PROGRESS -> IN_REVIEW
        patchStatus(ticketId, "IN_REVIEW", devToken).andExpect(status().isOk());

        // IN_REVIEW -> DONE
        patchStatus(ticketId, "DONE", devToken).andExpect(status().isOk());

        // DONE locks further updates: attempt to change anything -> 409
        mockMvc.perform(patch("/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "title": "Renamed after DONE" }
                            """))
                .andExpect(status().isConflict());

        // Soft delete (any authenticated user can delete per current rules)
        mockMvc.perform(delete("/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk());

        // Force the @SQLDelete UPDATE to flush and evict the managed entity,
        // so the subsequent findById issues a fresh SELECT that @SQLRestriction
        // can filter. Without this, the cached entity is returned with its
        // in-memory deleted_at=null and the test sees 200.
        entityManager.flush();
        entityManager.clear();

        // GET now 404 (filtered by @SQLRestriction)
        mockMvc.perform(get("/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNotFound());

        // ADMIN can list deleted
        mockMvc.perform(get("/tickets/deleted")
                        .param("projectId", projectId.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + ticketId + ")]").exists());

        // ADMIN can restore
        mockMvc.perform(post("/tickets/" + ticketId + "/restore")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        entityManager.flush();
        entityManager.clear();

        // GET succeeds again
        mockMvc.perform(get("/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk());
    }

    @Test
    void backwardTransition_rejectedWith409() throws Exception {
        Long ticketId = createTicket();
        patchStatus(ticketId, "IN_PROGRESS", devToken).andExpect(status().isOk());

        // IN_PROGRESS -> TODO is illegal
        patchStatus(ticketId, "TODO", devToken).andExpect(status().isConflict());
    }

    @Test
    void developerRestore_rejectedWith403() throws Exception {
        Long ticketId = createTicket();
        mockMvc.perform(delete("/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk());

        // DEVELOPER attempting restore -> 403
        mockMvc.perform(post("/tickets/" + ticketId + "/restore")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden());
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private Long createTicket() throws Exception {
        String body = """
            {
              "title": "Bug X",
              "description": "desc",
              "status": "TODO",
              "priority": "LOW",
              "type": "BUG",
              "projectId": %d
            }
            """.formatted(projectId);

        MvcResult r = mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resp = json.readTree(r.getResponse().getContentAsString());
        return resp.get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions patchStatus(
            Long ticketId, String status, String token) throws Exception {
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