package com.att.tdp.issueflow.ticket.attachment;

import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Round-trip: upload an attachment via multipart, list, download, delete.
 * Verifies that the bytes returned by download match what was uploaded —
 * the strongest possible proof that the BYTEA storage + content-type
 * handling + multipart parsing all cooperate correctly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AttachmentRoundTripIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired TicketRepository ticketRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String RAW_PASSWORD = "password123";
    private Long ticketId;
    private Long uploaderId;
    private String token;

    @BeforeEach
    void seed() throws Exception {
        User admin = userRepository.save(new User(
                "admin", "admin@x.com",
                passwordEncoder.encode(RAW_PASSWORD),
                "Admin", UserRole.ADMIN));
        uploaderId = admin.getId();

        Project p = projectRepository.save(new Project("Proj", "d", admin));
        Ticket t = ticketRepository.save(new Ticket(
                "T", "d", TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                p, null, null));
        ticketId = t.getId();

        token = login("admin");
    }

    @Test
    void roundTrip_uploadThenListThenDownloadThenDelete() throws Exception {
        byte[] payload = "the-actual-png-bytes-here".getBytes();

        // UPLOAD
        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png", payload);

        MvcResult upload = mockMvc.perform(multipart("/tickets/" + ticketId + "/attachments")
                        .file(file)
                        .param("uploaderId", uploaderId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("logo.png"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.sizeBytes").value(payload.length))
                .andReturn();

        JsonNode resp = json.readTree(upload.getResponse().getContentAsString());
        Long attachmentId = resp.get("id").asLong();

        // LIST — metadata only, no bytes
        mockMvc.perform(get("/tickets/" + ticketId + "/attachments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(attachmentId))
                .andExpect(jsonPath("$[0].filename").value("logo.png"));

        // DOWNLOAD — bytes match
        mockMvc.perform(get("/tickets/" + ticketId + "/attachments/" + attachmentId + "/download")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("image/png")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("logo.png")))
                .andExpect(content().bytes(payload));

        // DELETE
        mockMvc.perform(delete("/tickets/" + ticketId + "/attachments/" + attachmentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // LIST — now empty
        mockMvc.perform(get("/tickets/" + ticketId + "/attachments")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void upload_rejectsDisallowedMimeType_with400() throws Exception {
        MockMultipartFile bad = new MockMultipartFile(
                "file", "archive.zip", "application/zip", "data".getBytes());

        mockMvc.perform(multipart("/tickets/" + ticketId + "/attachments")
                        .file(bad)
                        .param("uploaderId", uploaderId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_rejectsEmptyFile_with400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.png", "image/png", new byte[0]);

        mockMvc.perform(multipart("/tickets/" + ticketId + "/attachments")
                        .file(empty)
                        .param("uploaderId", uploaderId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    private String login(String username) throws Exception {
        MvcResult r = mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("""
                            { "username": "%s", "password": "%s" }
                            """.formatted(username, RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}