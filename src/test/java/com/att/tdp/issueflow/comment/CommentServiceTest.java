package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.comment.dto.CommentResponse;
import com.att.tdp.issueflow.comment.dto.CreateCommentRequest;
import com.att.tdp.issueflow.comment.dto.MentionsPageResponse;
import com.att.tdp.issueflow.comment.dto.UpdateCommentRequest;
import com.att.tdp.issueflow.common.JpaConfig;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice test for CommentService. Focus areas:
 *   - mention recomputation on update (add and remove)
 *   - cannot comment on a soft-deleted ticket
 *   - author validation
 *   - cross-ticket comment access is rejected (404)
 *   - paginated mentions lookup
 *   - @Version optimistic locking on Comment via detach+merge pattern
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommentService.class, MentionParser.class, JpaConfig.class})
class CommentServiceTest {

    @Autowired CommentService commentService;
    @Autowired CommentRepository commentRepository;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @PersistenceContext EntityManager entityManager;

    private Long ticketId;
    private Long aliceId;
    private Long bobId;
    private Long authorId;

    @BeforeEach
    void seedFixture() {
        User owner = userRepository.save(new User(
                "owner", "owner@x.com", "h", "Owner", UserRole.ADMIN));
        User alice = userRepository.save(new User(
                "alice", "alice@x.com", "h", "Alice", UserRole.DEVELOPER));
        User bob = userRepository.save(new User(
                "bob", "bob@x.com", "h", "Bob", UserRole.DEVELOPER));
        User author = userRepository.save(new User(
                "author", "author@x.com", "h", "Author", UserRole.DEVELOPER));

        Project project = projectRepository.save(new Project("P", "d", owner));
        Ticket ticket = ticketRepository.save(new Ticket(
                "T", "d",
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                project, null, null));

        this.ticketId = ticket.getId();
        this.aliceId = alice.getId();
        this.bobId = bob.getId();
        this.authorId = author.getId();
    }

    // ─── Create ─────────────────────────────────────────────────────────

    @Test
    void create_persistsComment_andResolvesMentions() {
        CommentResponse created = commentService.create(ticketId,
                new CreateCommentRequest(authorId, "Hi @alice and @bob"));

        assertThat(created.id()).isNotNull();
        assertThat(created.mentionedUsers()).hasSize(2)
                .extracting(m -> m.username())
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void create_silentlyDropsUnknownMentions() {
        CommentResponse created = commentService.create(ticketId,
                new CreateCommentRequest(authorId, "Hi @alice and @nosuchuser"));

        assertThat(created.mentionedUsers()).extracting(m -> m.username())
                .containsExactly("alice");
    }

    @Test
    void create_throwsNotFound_whenTicketMissing() {
        assertThatThrownBy(() -> commentService.create(99_999L,
                new CreateCommentRequest(authorId, "x")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Ticket");
    }

    @Test
    void create_throwsNotFound_whenAuthorMissing() {
        assertThatThrownBy(() -> commentService.create(ticketId,
                new CreateCommentRequest(99_999L, "x")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User");
    }

    @Test
    void create_throwsNotFound_whenTicketIsSoftDeleted() {
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();
        ticket.markDeleted();
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> commentService.create(ticketId,
                new CreateCommentRequest(authorId, "x")))
                .isInstanceOf(NotFoundException.class);
    }

    // ─── Update + mention recomputation ─────────────────────────────────

    @Test
    void update_recomputesMentions_addingNewOnes() {
        CommentResponse created = commentService.create(ticketId,
                new CreateCommentRequest(authorId, "Hi @alice"));

        commentService.update(ticketId, created.id(),
                new UpdateCommentRequest("Hi @alice and @bob"));
        entityManager.flush();
        entityManager.clear();

        List<CommentResponse> comments = commentService.findByTicket(ticketId);
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).mentionedUsers())
                .extracting(m -> m.username())
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void update_recomputesMentions_removingOldOnes() {
        CommentResponse created = commentService.create(ticketId,
                new CreateCommentRequest(authorId, "Hi @alice and @bob"));

        commentService.update(ticketId, created.id(),
                new UpdateCommentRequest("Hi @alice only"));
        entityManager.flush();
        entityManager.clear();

        List<CommentResponse> comments = commentService.findByTicket(ticketId);
        assertThat(comments.get(0).mentionedUsers())
                .extracting(m -> m.username())
                .containsExactly("alice");
    }

    @Test
    void update_throwsNotFound_whenCommentBelongsToDifferentTicket() {
        CommentResponse created = commentService.create(ticketId,
                new CreateCommentRequest(authorId, "Hi"));

        // Create a second ticket; PATCH the comment under the wrong ticket id.
        Project p = projectRepository.findAll().get(0);
        Ticket other = ticketRepository.save(new Ticket(
                "T2", "d", TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                p, null, null));

        assertThatThrownBy(() -> commentService.update(other.getId(), created.id(),
                new UpdateCommentRequest("new")))
                .isInstanceOf(NotFoundException.class);
    }

    // ─── @Version optimistic locking ────────────────────────────────────

    @Test
    void update_concurrentModification_triggersOptimisticLockingFailure() {
        CommentResponse created = commentService.create(ticketId,
                new CreateCommentRequest(authorId, "Hi"));
        entityManager.flush();
        entityManager.clear();

        Comment loadedA = entityManager.find(Comment.class, created.id());
        entityManager.detach(loadedA);

        Comment loadedB = entityManager.find(Comment.class, created.id());
        loadedB.updateContent("from-B");
        entityManager.flush();

        loadedA.updateContent("from-A");
        // JPA's OptimisticLockException fires at the explicit flush;
        // Spring's wrapper fires at commit. HTTP-layer translation to 409
        // is verified in CommentControllerTest.
        assertThatThrownBy(() -> {
            entityManager.merge(loadedA);
            entityManager.flush();
        }).isInstanceOf(OptimisticLockException.class);
    }

    // ─── Delete ─────────────────────────────────────────────────────────

    @Test
    void delete_removesComment_andCascadesToMentions() {
        CommentResponse created = commentService.create(ticketId,
                new CreateCommentRequest(authorId, "Hi @alice @bob"));

        commentService.delete(ticketId, created.id());
        entityManager.flush();
        entityManager.clear();

        assertThat(commentRepository.findById(created.id())).isEmpty();
        // Mention rows are cascade-removed; the user rows still exist.
        assertThat(userRepository.findById(aliceId)).isPresent();
    }

    // ─── findByTicket ───────────────────────────────────────────────────

    @Test
    void findByTicket_throwsNotFound_forMissingTicket() {
        assertThatThrownBy(() -> commentService.findByTicket(99_999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void findByTicket_returnsCommentsOrderedByCreatedAtAsc() {
        CommentResponse first = commentService.create(ticketId,
                new CreateCommentRequest(authorId, "first"));
        CommentResponse second = commentService.create(ticketId,
                new CreateCommentRequest(authorId, "second"));

        List<CommentResponse> result = commentService.findByTicket(ticketId);

        assertThat(result).extracting(CommentResponse::id)
                .containsExactly(first.id(), second.id());
    }

    // ─── Mentions endpoint ──────────────────────────────────────────────

    @Test
    void findMentionsFor_returnsNewestFirst_paginated() {
        commentService.create(ticketId,
                new CreateCommentRequest(authorId, "old mention @alice"));
        commentService.create(ticketId,
                new CreateCommentRequest(authorId, "newer mention @alice"));

        MentionsPageResponse result = commentService.findMentionsFor(aliceId, 1, 10);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.page()).isEqualTo(1);
        // Newest first per the README contract.
        assertThat(result.data().get(0).content()).isEqualTo("newer mention @alice");
    }

    @Test
    void findMentionsFor_throwsNotFound_forMissingUser() {
        assertThatThrownBy(() -> commentService.findMentionsFor(99_999L, 1, 10))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void findMentionsFor_userWithNoMentions_returnsEmpty() {
        MentionsPageResponse result = commentService.findMentionsFor(bobId, 1, 10);

        assertThat(result.total()).isZero();
        assertThat(result.data()).isEmpty();
    }
}