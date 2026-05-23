package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.common.error.GlobalExceptionHandler;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.config.SecurityConfig;
import com.att.tdp.issueflow.config.SecurityProblemHandlers;
import com.att.tdp.issueflow.config.TestSecurityConfig;
import com.att.tdp.issueflow.project.dto.ProjectResponse;
import com.att.tdp.issueflow.project.dto.UpdateProjectRequest;
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

/**
 * Slice test for ProjectController. Uses TestSecurityConfig (mirrors path
 * rules but skips the JWT filter). Service is mocked.
 *
 * Coverage focus: HTTP shape (per README contract), validation, error
 * mapping, and the ADMIN-only authorization on /deleted and /restore.
 */
@WebMvcTest(
    controllers = ProjectController.class,
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
class ProjectControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ProjectService projectService;

    // ─── Standard CRUD (any authenticated user) ─────────────────────────

    @Test
    void getAllProjects_returnsList() throws Exception {
        when(projectService.findAll()).thenReturn(List.of(
                new ProjectResponse(1L, "Apollo", "desc", 10L),
                new ProjectResponse(2L, "Gemini", "desc2", 11L)));

        mockMvc.perform(get("/projects").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Apollo"))
                .andExpect(jsonPath("$[1].ownerId").value(11));
    }

    @Test
    void getProject_returns404_whenMissing() throws Exception {
        when(projectService.findById(99L))
                .thenThrow(NotFoundException.of("Project", 99L));

        mockMvc.perform(get("/projects/99").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Project with id 99 not found"));
    }

    @Test
    void createProject_returns200_perReadmeContract() throws Exception {
        when(projectService.create(any())).thenReturn(
                new ProjectResponse(1L, "Apollo", "desc", 10L));

        String body = """
            { "name": "Apollo", "description": "desc", "ownerId": 10 }
            """;

        mockMvc.perform(post("/projects")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())   // 200, not 201
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void createProject_returns400_whenNameBlank() throws Exception {
        String body = """
            { "name": "", "description": "x", "ownerId": 1 }
            """;

        mockMvc.perform(post("/projects")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='name')]").exists());
    }

    @Test
    void createProject_returns400_whenOwnerIdMissing() throws Exception {
        String body = """
            { "name": "X", "description": "y" }
            """;

        mockMvc.perform(post("/projects")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='ownerId')]").exists());
    }

    @Test
    void updateProject_returnsEmptyBody_perReadmeContract() throws Exception {
        String body = """
            { "name": "Renamed", "description": "Updated" }
            """;

        mockMvc.perform(patch("/projects/1")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(projectService).update(eq(1L), any(UpdateProjectRequest.class));
    }

    @Test
    void softDeleteProject_returns200_withEmptyBody() throws Exception {
        mockMvc.perform(delete("/projects/1").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(projectService).softDelete(1L);
    }

    // ─── ADMIN-only endpoints ───────────────────────────────────────────

    @Test
    void getDeleted_returns403_forDeveloper() throws Exception {
        mockMvc.perform(get("/projects/deleted").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // Service should not have been called.
        verify(projectService, never()).findAllDeleted();
    }

    @Test
    void getDeleted_returns200_forAdmin() throws Exception {
        when(projectService.findAllDeleted()).thenReturn(List.of(
                new ProjectResponse(7L, "Old", "desc", 10L)));

        mockMvc.perform(get("/projects/deleted").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7));
    }

    @Test
    void restore_returns403_forDeveloper() throws Exception {
        mockMvc.perform(post("/projects/1/restore").with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isForbidden());

        verify(projectService, never()).restore(any());
    }

    @Test
    void restore_returns200_forAdmin() throws Exception {
        mockMvc.perform(post("/projects/1/restore").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(projectService).restore(1L);
    }
}