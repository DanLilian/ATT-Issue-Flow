package com.att.tdp.issueflow.ticket.attachment;

import com.att.tdp.issueflow.common.error.GlobalExceptionHandler;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.common.error.ValidationException;
import com.att.tdp.issueflow.config.SecurityConfig;
import com.att.tdp.issueflow.config.SecurityProblemHandlers;
import com.att.tdp.issueflow.config.TestSecurityConfig;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import com.att.tdp.issueflow.ticket.attachment.dto.AttachmentResponse;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = AttachmentController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {
            SecurityConfig.class,
            SecurityProblemHandlers.class,
            com.att.tdp.issueflow.auth.jwt.JwtAuthenticationFilter.class
        }
    )
)
@Import({GlobalExceptionHandler.class, TestSecurityConfig.class})
class AttachmentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AttachmentService attachmentService;

    @Test
    void getByTicket_returnsList() throws Exception {
        when(attachmentService.findByTicket(1L)).thenReturn(List.of(
                new AttachmentResponse(1L, "logo.png", "image/png", 1024, 5L)));

        mockMvc.perform(get("/tickets/1/attachments")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].filename").value("logo.png"))
                .andExpect(jsonPath("$[0].contentType").value("image/png"))
                .andExpect(jsonPath("$[0].sizeBytes").value(1024));
    }

    @Test
    void upload_returns200_andAttachmentMetadata() throws Exception {
        when(attachmentService.upload(eq(1L), eq(5L), any())).thenReturn(
                new AttachmentResponse(1L, "logo.png", "image/png", 1024, 5L));

        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png", "bytes".getBytes());

        mockMvc.perform(multipart("/tickets/1/attachments")
                        .file(file)
                        .param("uploaderId", "5")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.filename").value("logo.png"));
    }

    @Test
    void upload_returns400_onValidationFailure() throws Exception {
        when(attachmentService.upload(eq(1L), eq(5L), any()))
                .thenThrow(new ValidationException(
                        "Content type not allowed: application/zip"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "x.zip", "application/zip", "data".getBytes());

        mockMvc.perform(multipart("/tickets/1/attachments")
                        .file(file)
                        .param("uploaderId", "5")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Content type")));
    }

    @Test
    void upload_returns400_whenUploaderIdMissing() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.png", "image/png", "data".getBytes());

        mockMvc.perform(multipart("/tickets/1/attachments")
                        .file(file)
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void download_returnsBytesWithHeaders() throws Exception {
        byte[] content = "the-bytes".getBytes();

        // Build a real Attachment for the mock to return.
        User owner = new User("o", "o@x.com", "h", "O", UserRole.ADMIN);
        User uploader = new User("u", "u@x.com", "h", "U", UserRole.DEVELOPER);
        Project project = new Project("P", "d", owner);
        Ticket ticket = new Ticket("T", "d",
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                project, null, null);
        Attachment attachment = new Attachment(
                ticket, "report.pdf", "application/pdf",
                content.length, content, uploader);

        when(attachmentService.download(1L, 5L)).thenReturn(attachment);

        mockMvc.perform(get("/tickets/1/attachments/5/download")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("report.pdf")))
                .andExpect(content().bytes(content));
    }

    @Test
    void download_returns404_whenWrongTicket() throws Exception {
        when(attachmentService.download(1L, 5L))
                .thenThrow(NotFoundException.of("Attachment", 5L));

        mockMvc.perform(get("/tickets/1/attachments/5/download")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns200_withEmptyBody() throws Exception {
        mockMvc.perform(delete("/tickets/1/attachments/5")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(attachmentService).delete(1L, 5L);
    }

    @Test
    void delete_returns404_whenMissing() throws Exception {
        doThrow(NotFoundException.of("Attachment", 5L))
                .when(attachmentService).delete(1L, 5L);

        mockMvc.perform(delete("/tickets/1/attachments/5")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isNotFound());
    }
}