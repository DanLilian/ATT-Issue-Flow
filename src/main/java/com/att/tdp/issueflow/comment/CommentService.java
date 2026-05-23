package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.comment.dto.CommentResponse;
import com.att.tdp.issueflow.comment.dto.CreateCommentRequest;
import com.att.tdp.issueflow.comment.dto.MentionsPageResponse;
import com.att.tdp.issueflow.comment.dto.UpdateCommentRequest;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Business operations for {@link Comment}, including mention recomputation.
 *
 * Cannot comment on a soft-deleted ticket: ticketRepository.findById is
 * @SQLRestriction-filtered, so a soft-deleted ticket returns empty and the
 * service throws NotFoundException naturally.
 *
 * Mention recomputation on update relies on the entity's replaceMentions:
 * the orphanRemoval + CascadeType.ALL combination on Comment.mentions
 * (Phase 2) means Hibernate diffs the collection at flush — removed
 * entries become DELETE, new entries become INSERT, unchanged entries
 * are no-ops.
 *
 * Audit-log writes deferred to Phase 9.
 */
@Service
@Transactional(readOnly = true)
public class CommentService {

    private final CommentRepository commentRepository;
    private final CommentMentionRepository commentMentionRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final MentionParser mentionParser;

    public CommentService(CommentRepository commentRepository,
                          CommentMentionRepository commentMentionRepository,
                          TicketRepository ticketRepository,
                          UserRepository userRepository,
                          MentionParser mentionParser) {
        this.commentRepository = commentRepository;
        this.commentMentionRepository = commentMentionRepository;
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.mentionParser = mentionParser;
    }

    public List<CommentResponse> findByTicket(Long ticketId) {
        // Ensure the ticket exists & isn't soft-deleted. Same pattern as
        // TicketService.findByProject — explicit 404 beats silent [].
        if (ticketRepository.findById(ticketId).isEmpty()) {
            throw NotFoundException.of("Ticket", ticketId);
        }
        return commentRepository.findByTicketIdOrderByCreatedAtAsc(ticketId).stream()
                .map(CommentResponse::from)
                .toList();
    }

    @Transactional
    public CommentResponse create(Long ticketId, CreateCommentRequest req) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> NotFoundException.of("Ticket", ticketId));

        User author = userRepository.findById(req.authorId())
                .orElseThrow(() -> NotFoundException.of("User", req.authorId()));

        Comment comment = new Comment(ticket, author, req.content());
        comment.replaceMentions(mentionParser.resolveMentions(req.content()));

        Comment saved = commentRepository.save(comment);

        // TODO Phase 9: auditService.record(COMMENT_CREATE, COMMENT, saved.getId(), ...);

        return CommentResponse.from(saved);
    }

    @Transactional
    public void update(Long ticketId, Long commentId, UpdateCommentRequest req) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> NotFoundException.of("Comment", commentId));

        // Defensive check: the commentId must belong to the ticketId in the URL.
        // Otherwise PATCH /tickets/A/comments/X would mutate a comment that
        // actually belongs to ticket B — a path/data inconsistency we should
        // refuse rather than silently honor.
        if (!comment.getTicket().getId().equals(ticketId)) {
            throw NotFoundException.of("Comment", commentId);
        }

        comment.updateContent(req.content());

        // Recompute mentions from the new content. The entity's
        // replaceMentions clears the collection and re-adds; Hibernate's
        // flush diffs the result against the original.
        comment.replaceMentions(mentionParser.resolveMentions(req.content()));

        // No save() — managed entity, dirty checking on commit. @Version
        // detects concurrent modification at flush and throws.

        // TODO Phase 9: auditService.record(COMMENT_UPDATE, COMMENT, commentId, ...);
    }

    @Transactional
    public void delete(Long ticketId, Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> NotFoundException.of("Comment", commentId));

        if (!comment.getTicket().getId().equals(ticketId)) {
            throw NotFoundException.of("Comment", commentId);
        }

        // Hard delete is fine for comments — the spec doesn't list them
        // among the soft-deletable resources (only tickets and projects).
        // CascadeType.ALL + orphanRemoval on Comment.mentions takes care
        // of deleting the mention rows automatically.
        commentRepository.delete(comment);

        // TODO Phase 9: auditService.record(COMMENT_DELETE, COMMENT, commentId, ...);
    }

    /**
     * Paginated mentions for a user. Adapts Spring Data's 0-based Page
     * to the README's 1-based page contract.
     */
    public MentionsPageResponse findMentionsFor(Long userId, int page, int pageSize) {
        // Verify the user exists for a clean 404 instead of empty results.
        if (userRepository.findById(userId).isEmpty()) {
            throw NotFoundException.of("User", userId);
        }

        // README contract is 1-based; Spring is 0-based.
        int pageZeroBased = Math.max(0, page - 1);
        Pageable pageable = PageRequest.of(pageZeroBased, pageSize);
        Page<Comment> commentPage =
                commentMentionRepository.findCommentsMentioningUser(userId, pageable);

        List<CommentResponse> data = commentPage.getContent().stream()
                .map(CommentResponse::from)
                .toList();

        // Return the page back as 1-based to mirror what the caller sent.
        return new MentionsPageResponse(data, commentPage.getTotalElements(), page);
    }
}