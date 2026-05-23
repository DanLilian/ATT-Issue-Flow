package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.audit.dto.AuditLogFilter;
import com.att.tdp.issueflow.audit.dto.AuditLogResponse;
import com.att.tdp.issueflow.common.PageResponse;
import com.att.tdp.issueflow.common.error.GlobalExceptionHandler;
import com.att.tdp.issueflow.config.SecurityConfig;
import com.att.tdp.issueflow.config.SecurityProblemHandlers;
import com.att.tdp.issueflow.config.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = AuditController.class,
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
class AuditControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AuditService auditService;

    @Test
    void getLogs_returns403_forDeveloper() throws Exception {
        mockMvc.perform(get("/audit-logs").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isForbidden());

        verify(auditService, never()).find(any(), anyInt(), anyInt());
    }

    @Test
    void getLogs_returns200_forAdmin() throws Exception {
        when(auditService.find(any(), eq(1), eq(20))).thenReturn(
                new PageResponse<>(
                    List.of(new AuditLogResponse(1L, AuditAction.TICKET_CREATE,
                            AuditEntityType.TICKET, 5L, AuditActor.USER, 42L,
                            null, Instant.parse("2026-05-23T10:00:00Z"))),
                    1L, 1));

        mockMvc.perform(get("/audit-logs").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].action").value("TICKET_CREATE"))
                .andExpect(jsonPath("$.data[0].entityType").value("TICKET"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void getLogs_passesFiltersThrough() throws Exception {
        when(auditService.find(any(), eq(1), eq(20))).thenReturn(
                new PageResponse<>(List.of(), 0L, 1));

        mockMvc.perform(get("/audit-logs"
                        + "?entityType=TICKET&entityId=42&action=TICKET_CREATE&actor=USER")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLogFilter> filterCaptor = ArgumentCaptor.forClass(AuditLogFilter.class);
        verify(auditService).find(filterCaptor.capture(), eq(1), eq(20));

        AuditLogFilter captured = filterCaptor.getValue();
        assertThat(captured.entityType()).isEqualTo(AuditEntityType.TICKET);
        assertThat(captured.entityId()).isEqualTo(42L);
        assertThat(captured.action()).isEqualTo(AuditAction.TICKET_CREATE);
        assertThat(captured.actor()).isEqualTo(AuditActor.USER);
    }

    @Test
    void getLogs_acceptsPaginationParams() throws Exception {
        when(auditService.find(any(), eq(3), eq(5))).thenReturn(
                new PageResponse<>(List.of(), 0L, 3));

        mockMvc.perform(get("/audit-logs?page=3&pageSize=5")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(auditService).find(any(), eq(3), eq(5));
    }
}