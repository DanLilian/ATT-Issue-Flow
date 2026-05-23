package com.att.tdp.issueflow.ticket.dependency;

import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.GlobalExceptionHandler;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.config.SecurityConfig;
import com.att.tdp.issueflow.config.SecurityProblemHandlers;
import com.att.tdp.issueflow.config.TestSecurityConfig;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.dependency.dto.DependencyResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
    controllers = TicketDependencyController.class,
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
class TicketDependencyControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean TicketDependencyService dependencyService;

    @Test
    void getDependencies_returnsList() throws Exception {
        when(dependencyService.findByTicket(1L)).thenReturn(List.of(
                new DependencyResponse(2L, "Blocker", TicketStatus.IN_PROGRESS)));

        mockMvc.perform(get("/tickets/1/dependencies")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].blockerId").value(2))
                .andExpect(jsonPath("$[0].blockerTitle").value("Blocker"))
                .andExpect(jsonPath("$[0].blockerStatus").value("IN_PROGRESS"));
    }

    @Test
    void addDependency_returns200_perReadmeContract() throws Exception {
        when(dependencyService.add(eq(1L), any())).thenReturn(
                new DependencyResponse(2L, "Blocker", TicketStatus.TODO));

        String body = """
            { "blockedBy": 2 }
            """;

        mockMvc.perform(post("/tickets/1/dependencies")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockerId").value(2));
    }

    @Test
    void addDependency_returns400_whenBlockedByMissing() throws Exception {
        String body = """
            { }
            """;

        mockMvc.perform(post("/tickets/1/dependencies")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='blockedBy')]").exists());
    }

    @Test
    void addDependency_returns409_onSelfBlock() throws Exception {
        when(dependencyService.add(eq(1L), any()))
                .thenThrow(new ConflictException("A ticket cannot block itself"));

        String body = """
            { "blockedBy": 1 }
            """;

        mockMvc.perform(post("/tickets/1/dependencies")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A ticket cannot block itself"));
    }

    @Test
    void addDependency_returns409_onCycle() throws Exception {
        when(dependencyService.add(eq(1L), any()))
                .thenThrow(new ConflictException(
                        "Dependency would create a cycle: 2 cannot block 1"));

        String body = """
            { "blockedBy": 2 }
            """;

        mockMvc.perform(post("/tickets/1/dependencies")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("cycle")));
    }

    @Test
    void removeDependency_returns200_withEmptyBody() throws Exception {
        mockMvc.perform(delete("/tickets/1/dependencies/2")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(dependencyService).remove(1L, 2L);
    }

    @Test
    void removeDependency_returns404_whenMissing() throws Exception {
        doThrow(NotFoundException.of("Dependency", 1L))
                .when(dependencyService).remove(1L, 2L);

        mockMvc.perform(delete("/tickets/1/dependencies/2")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isNotFound());
    }
}