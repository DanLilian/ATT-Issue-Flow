package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.comment.dto.CommentResponse;
import com.att.tdp.issueflow.comment.dto.CreateCommentRequest;
import com.att.tdp.issueflow.comment.dto.UpdateCommentRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for ticket comments. Mounted under /tickets/{ticketId}/...
 * so the URL path expresses the parent-child relationship — matches the
 * README contract exactly.
 *
 * Endpoint shapes follow the README:
 *   GET    /tickets/{tid}/comments
 *   POST   /tickets/{tid}/comments           200 (not 201)
 *   PATCH  /tickets/{tid}/comments/{cid}     200, empty body
 *   DELETE /tickets/{tid}/comments/{cid}     200, empty body
 */
@RestController
@RequestMapping("/tickets/{ticketId}/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    public List<CommentResponse> getByTicket(@PathVariable Long ticketId) {
        return commentService.findByTicket(ticketId);
    }

    @PostMapping
    public CommentResponse create(@PathVariable Long ticketId,
                                  @Valid @RequestBody CreateCommentRequest req) {
        return commentService.create(ticketId, req);
    }

    @PatchMapping("/{commentId}")
    public ResponseEntity<Void> update(@PathVariable Long ticketId,
                                       @PathVariable Long commentId,
                                       @Valid @RequestBody UpdateCommentRequest req) {
        commentService.update(ticketId, commentId, req);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable Long ticketId,
                                       @PathVariable Long commentId) {
        commentService.delete(ticketId, commentId);
        return ResponseEntity.ok().build();
    }
}