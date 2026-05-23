package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.GlobalExceptionHandler;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.config.SecurityConfig;
import com.att.tdp.issueflow.config.SecurityProblemHandlers;
import com.att.tdp.issueflow.config.TestSecurityConfig;
import com.att.tdp.issueflow.ticket.dto.TicketResponse;
import com.att.tdp.issueflow.ticket.dto.UpdateTicketRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = TicketController.class,
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
class TicketControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean TicketService ticketService;

    // ─── Standard CRUD ──────────────────────────────────────────────────

    @Test
    void getByProject_returnsList() throws Exception {
        when(ticketService.findByProject(1L)).thenReturn(List.of(
                new TicketResponse(1L, "Bug", "d", TicketStatus.TODO,
                        TicketPriority.HIGH, TicketType.BUG, 1L, 2L,
                        Instant.parse("2026-12-01T00:00:00Z"), false)));

        mockMvc.perform(get("/tickets?projectId=1").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Bug"))
                // ISO-8601 with Z per README contract.
                .andExpect(jsonPath("$[0].dueDate").value("2026-12-01T00:00:00Z"))
                .andExpect(jsonPath("$[0].isOverdue").value(false));
    }

    @Test
    void getByProject_returns400_whenProjectIdMissing() throws Exception {
        mockMvc.perform(get("/tickets").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("projectId")));
    }

    @Test
    void getTicket_returns404_whenMissing() throws Exception {
        when(ticketService.findById(99L)).thenThrow(NotFoundException.of("Ticket", 99L));

        mockMvc.perform(get("/tickets/99").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void createTicket_returns200_perReadmeContract() throws Exception {
        when(ticketService.create(any())).thenReturn(
                new TicketResponse(1L, "Bug", "d", TicketStatus.TODO,
                        TicketPriority.HIGH, TicketType.BUG, 1L, 2L, null, false));

        String body = """
            {
              "title": "Bug",
              "description": "d",
              "status": "TODO",
              "priority": "HIGH",
              "type": "BUG",
              "projectId": 1,
              "assigneeId": 2
            }
            """;

        mockMvc.perform(post("/tickets")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void createTicket_returns400_whenStatusMissing() throws Exception {
        String body = """
            {
              "title": "Bug",
              "priority": "HIGH",
              "type": "BUG",
              "projectId": 1
            }
            """;

        mockMvc.perform(post("/tickets")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='status')]").exists());
    }

    @Test
    void createTicket_returns400_whenStatusInvalidEnum() throws Exception {
        String body = """
            {
              "title": "Bug",
              "status": "WHENEVER",
              "priority": "HIGH",
              "type": "BUG",
              "projectId": 1
            }
            """;

        mockMvc.perform(post("/tickets")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("status")));
    }

    @Test
    void updateTicket_returnsEmptyBody_perReadmeContract() throws Exception {
        String body = """
            { "status": "IN_PROGRESS" }
            """;

        mockMvc.perform(patch("/tickets/1")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(ticketService).update(eq(1L), any(UpdateTicketRequest.class));
    }

    @Test
    void updateTicket_returns409_onIllegalStatusTransition() throws Exception {
        doThrow(new ConflictException("Illegal status transition: TODO -> DONE"))
                .when(ticketService).update(eq(1L), any());

        String body = """
            { "status": "DONE" }
            """;

        mockMvc.perform(patch("/tickets/1")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Illegal status transition: TODO -> DONE"));
    }

    @Test
    void updateTicket_returns409_whenTicketIsDone() throws Exception {
        doThrow(new ConflictException("Ticket is DONE and cannot be updated"))
                .when(ticketService).update(eq(1L), any());

        String body = """
            { "title": "renamed" }
            """;

        mockMvc.perform(patch("/tickets/1")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("DONE")));
    }

    @Test
    void updateTicket_returns409_onOptimisticLockingFailure() throws Exception {
        // Verifies the wiring from OptimisticLockingFailureException
        // (thrown by Hibernate on @Version conflict) to 409 via the
        // global handler.
        doThrow(new OptimisticLockingFailureException("stale"))
                .when(ticketService).update(eq(1L), any());

        String body = """
            { "title": "renamed" }
            """;

        mockMvc.perform(patch("/tickets/1")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Resource was modified by another user. Reload and retry."));
    }

    @Test
    void softDeleteTicket_returns200_withEmptyBody() throws Exception {
        mockMvc.perform(delete("/tickets/1").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(ticketService).softDelete(1L);
    }

    // ─── ADMIN-only endpoints ───────────────────────────────────────────

    @Test
    void getDeleted_returns403_forDeveloper() throws Exception {
        mockMvc.perform(get("/tickets/deleted?projectId=1")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isForbidden());

        verify(ticketService, never()).findAllDeletedByProject(any());
    }

    @Test
    void getDeleted_returns200_forAdmin() throws Exception {
        when(ticketService.findAllDeletedByProject(1L)).thenReturn(List.of());

        mockMvc.perform(get("/tickets/deleted?projectId=1")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void restore_returns403_forDeveloper() throws Exception {
        mockMvc.perform(post("/tickets/1/restore")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isForbidden());

        verify(ticketService, never()).restore(any());
    }

    @Test
    void restore_returns200_forAdmin() throws Exception {
        mockMvc.perform(post("/tickets/1/restore")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(ticketService).restore(1L);
    }
}