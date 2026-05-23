package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.common.BaseEntity;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false, length = 5000)
    private String content;

    /**
     * Mentions cascade with the comment: creating a comment persists its
     * mentions; deleting a comment deletes them; clearing-and-replacing
     * the set on update deletes removed mentions and inserts new ones
     * (orphanRemoval = true). This is exactly the semantics PDF 3.6 requires.
     */
    @OneToMany(
        mappedBy = "comment",
        cascade = CascadeType.ALL,
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    private Set<CommentMention> mentions = new HashSet<>();

    @Version
    @Column(nullable = false)
    private Long version;

    public Comment(Ticket ticket, User author, String content) {
        this.ticket = ticket;
        this.author = author;
        this.content = content;
    }

    public void updateContent(String content) {
        this.content = content;
    }

    /**
     * Replaces the mention set wholesale, diffing against the existing
     * collection so unchanged entries keep their identity (and their
     * existing row in the DB).
     *
     * IMPORTANT — why not clear() + add():
     * Hibernate's ActionQueue groups INSERTs before DELETEs within a flush.
     * A naive clear() + add(new CommentMention(this, alice)) when alice was
     * already mentioned issues the INSERT first and trips the unique
     * constraint on (comment_id, mentioned_user_id) before the DELETE runs.
     *
     * The correct pattern: remove only entries whose user is NOT in the
     * new set (orphanRemoval handles the DELETE), then add only entries
     * for users not already represented. Existing rows for unchanged
     * mentions are left alone — no churn, no constraint violation.
     */
    public void replaceMentions(Set<User> mentionedUsers) {
        // Remove mentions whose user is no longer in the new set.
        // Uses iterator.remove so orphanRemoval fires on the deleted entries.
        this.mentions.removeIf(m -> !mentionedUsers.contains(m.getMentionedUser()));

        // Collect users who already have a mention row, so we don't re-add them.
        Set<User> existing = this.mentions.stream()
                .map(CommentMention::getMentionedUser)
                .collect(java.util.stream.Collectors.toSet());

        // Add only genuinely new mentions.
        for (User u : mentionedUsers) {
            if (!existing.contains(u)) {
                this.mentions.add(new CommentMention(this, u));
            }
        }
    }
}