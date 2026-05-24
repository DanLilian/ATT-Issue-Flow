package com.att.tdp.issueflow.ticket.csv;

import com.att.tdp.issueflow.common.error.GlobalExceptionHandler;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.common.error.ValidationException;
import com.att.tdp.issueflow.config.SecurityConfig;
import com.att.tdp.issueflow.config.SecurityProblemHandlers;
import com.att.tdp.issueflow.config.TestSecurityConfig;
import com.att.tdp.issueflow.ticket.csv.dto.ImportErrorEntry;
import com.att.tdp.issueflow.ticket.csv.dto.ImportResultResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
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
    controllers = TicketCsvController.class,
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
class TicketCsvControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean TicketCsvService csvService;

    @Test
    void export_returnsCsvWithCorrectHeaders() throws Exception {
        byte[] csv = "id,title,description,status,priority,type,assigneeId\n".getBytes();
        when(csvService.export(1L)).thenReturn(csv);

        mockMvc.perform(get("/tickets/export")
                        .param("projectId", "1")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("tickets-project-1.csv")))
                .andExpect(content().bytes(csv));
    }

    @Test
    void export_returns400_whenProjectIdMissing() throws Exception {
        mockMvc.perform(get("/tickets/export")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void export_returns404_whenProjectMissing() throws Exception {
        when(csvService.export(99L)).thenThrow(NotFoundException.of("Project", 99L));

        mockMvc.perform(get("/tickets/export")
                        .param("projectId", "99")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void import_returns200_andSummary() throws Exception {
        when(csvService.importCsv(eq(1L), any())).thenReturn(
                new ImportResultResponse(2, 1, List.of(
                    new ImportErrorEntry(3L, "status has invalid value 'WIZARD'"))));

        MockMultipartFile file = new MockMultipartFile(
                "file", "tickets.csv", "text/csv",
                "title,description,status,priority,type,assigneeId\n".getBytes());

        mockMvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", "1")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors[0].row").value(3))
                .andExpect(jsonPath("$.errors[0].reason").value(
                        org.hamcrest.Matchers.containsString("WIZARD")));
    }

    @Test
    void import_returns400_onValidationFailure() throws Exception {
        when(csvService.importCsv(eq(1L), any()))
                .thenThrow(new ValidationException(
                        "CSV is missing required columns: [status]"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.csv", "text/csv", "title\nT1\n".getBytes());

        mockMvc.perform(multipart("/tickets/import")
                        .file(file)
                        .param("projectId", "1")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("missing required columns")));
    }

    @Test
    void import_returns400_whenProjectIdMissing() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.csv", "text/csv", "title\n".getBytes());

        mockMvc.perform(multipart("/tickets/import")
                        .file(file)
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isBadRequest());
    }
}