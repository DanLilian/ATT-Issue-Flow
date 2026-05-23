package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.comment.dto.MentionsPageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hosts the GET /users/{userId}/mentions endpoint. Lives in the comment
 * package because the underlying data (Comment + CommentMention) lives
 * there too — the user package would otherwise have to depend on the
 * comment service, which is structurally awkward.
 *
 * Spring routes requests by path mapping, not by class, so the endpoint
 * URL is /users/{userId}/mentions regardless of which controller hosts it.
 */
@RestController
@RequestMapping("/users/{userId}/mentions")
public class MentionController {

    private final CommentService commentService;

    public MentionController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    public MentionsPageResponse getMentions(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return commentService.findMentionsFor(userId, page, pageSize);
    }
}