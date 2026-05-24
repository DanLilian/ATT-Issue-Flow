package com.att.tdp.issueflow.ticket.csv;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CSV export + import round-trip via HTTP. Specifically proves that the
 * partial-failure semantics and the multipart import shape work over the
 * real wire format, not just the slice-tested service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CsvRoundTripIntegrationTest {

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
    void export_returnsCsvWithExpectedHeader() throws Exception {
        // Create one ticket so export has content.
        createTicket("T1");

        MvcResult r = mockMvc.perform(get("/tickets/export")
                        .param("projectId", projectId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("text/csv")))
                .andReturn();

        String csv = r.getResponse().getContentAsString();
        assertThat(csv)
                .startsWith("id,title,description,status,priority,type,assigneeId")
                .contains("T1");
    }

    @Test
    void import_withMixedRows_persistsGoodAndReportsBad() throws Exception {
        // 3 good rows + 1 with invalid enum.
        String csvBody = """
            title,description,status,priority,type,assigneeId
            Good 1,d1,TODO,LOW,BUG,
            Good 2,d2,IN_PROGRESS,HIGH,FEATURE,
            Bad Status,d3,WIZARD,LOW,BUG,
            Good 3,d4,TODO,LOW,TECHNICAL,
            """;

        MockMultipartFile file = new MockMultipartFile(
                "file", "import.csv", "text/csv", csvBody.getBytes());

        mockMvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", projectId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors[0].row").value(3))
                .andExpect(jsonPath("$.errors[0].reason").value(
                        org.hamcrest.Matchers.containsString("WIZARD")));

        // Verify the 3 good rows actually persisted by listing.
        MvcResult list = mockMvc.perform(get("/tickets")
                        .param("projectId", projectId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode arr = json.readTree(list.getResponse().getContentAsString());
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isEqualTo(3);
    }

    @Test
    void import_rejectsCsvMissingRequiredColumn_with400() throws Exception {
        String csvBody = """
            title,description,priority,type,assigneeId
            T1,d,LOW,BUG,
            """;   // missing 'status'

        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.csv", "text/csv", csvBody.getBytes());

        mockMvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", projectId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("status")));
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private void createTicket(String title) throws Exception {
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

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
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