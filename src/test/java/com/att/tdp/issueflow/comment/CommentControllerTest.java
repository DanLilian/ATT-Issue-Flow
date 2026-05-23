package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.comment.dto.CommentResponse;
import com.att.tdp.issueflow.comment.dto.MentionedUserView;
import com.att.tdp.issueflow.comment.dto.MentionsPageResponse;
import com.att.tdp.issueflow.comment.dto.UpdateCommentRequest;
import com.att.tdp.issueflow.common.error.GlobalExceptionHandler;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.config.SecurityConfig;
import com.att.tdp.issueflow.config.SecurityProblemHandlers;
import com.att.tdp.issueflow.config.TestSecurityConfig;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = { CommentController.class, MentionController.class },
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
class CommentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CommentService commentService;

    @Test
    void getComments_returnsListWithMentionedUsers() throws Exception {
        when(commentService.findByTicket(1L)).thenReturn(List.of(
                new CommentResponse(1L, 1L, 2L, "Hello @alice",
                        List.of(new MentionedUserView(3L, "alice", "Alice")))));

        mockMvc.perform(get("/tickets/1/comments")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("Hello @alice"))
                .andExpect(jsonPath("$[0].mentionedUsers[0].username").value("alice"));
    }

    @Test
    void createComment_returns200_perReadmeContract() throws Exception {
        when(commentService.create(eq(1L), any())).thenReturn(
                new CommentResponse(5L, 1L, 2L, "Hi", List.of()));

        String body = """
            { "authorId": 2, "content": "Hi" }
            """;

        mockMvc.perform(post("/tickets/1/comments")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void createComment_returns400_whenContentBlank() throws Exception {
        String body = """
            { "authorId": 2, "content": "" }
            """;

        mockMvc.perform(post("/tickets/1/comments")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='content')]").exists());
    }

    @Test
    void createComment_returns400_whenAuthorIdMissing() throws Exception {
        String body = """
            { "content": "Hi" }
            """;

        mockMvc.perform(post("/tickets/1/comments")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='authorId')]").exists());
    }

    @Test
    void createComment_returns404_whenTicketMissing() throws Exception {
        when(commentService.create(eq(99L), any()))
                .thenThrow(NotFoundException.of("Ticket", 99L));

        String body = """
            { "authorId": 2, "content": "Hi" }
            """;

        mockMvc.perform(post("/tickets/99/comments")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateComment_returnsEmptyBody() throws Exception {
        String body = """
            { "content": "Updated" }
            """;

        mockMvc.perform(patch("/tickets/1/comments/2")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(commentService).update(eq(1L), eq(2L), any(UpdateCommentRequest.class));
    }

    @Test
    void updateComment_returns409_onOptimisticLockingFailure() throws Exception {
        doThrow(new OptimisticLockingFailureException("stale"))
                .when(commentService).update(eq(1L), eq(2L), any());

        String body = """
            { "content": "X" }
            """;

        mockMvc.perform(patch("/tickets/1/comments/2")
                        .with(user("dev").roles("DEVELOPER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Resource was modified by another user. Reload and retry."));
    }

    @Test
    void deleteComment_returns200_withEmptyBody() throws Exception {
        mockMvc.perform(delete("/tickets/1/comments/2")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(commentService).delete(1L, 2L);
    }

    // ─── Mention endpoint ───────────────────────────────────────────────

    @Test
    void getMentions_returnsPaginatedShape_perReadme() throws Exception {
        when(commentService.findMentionsFor(eq(5L), eq(1), eq(20)))
                .thenReturn(new MentionsPageResponse(
                        List.of(new CommentResponse(1L, 3L, 2L, "@alice",
                                List.of(new MentionedUserView(5L, "alice", "Alice")))),
                        10L, 1));

        mockMvc.perform(get("/users/5/mentions")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void getMentions_acceptsPageAndPageSize() throws Exception {
        when(commentService.findMentionsFor(eq(5L), eq(2), eq(5)))
                .thenReturn(new MentionsPageResponse(List.of(), 0L, 2));

        mockMvc.perform(get("/users/5/mentions?page=2&pageSize=5")
                        .with(user("dev").roles("DEVELOPER")))
                .andExpect(status().isOk());

        verify(commentService).findMentionsFor(5L, 2, 5);
    }
}