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
     * Replaces the mention set wholesale. Hibernate will diff the
     * collection on flush: existing-but-removed entries are deleted,
     * new entries are inserted, unchanged entries are untouched.
     *
     * Called by CommentService after parsing the (possibly new) content.
     */
    public void replaceMentions(Set<User> mentionedUsers) {
        this.mentions.clear();
        for (User u : mentionedUsers) {
            this.mentions.add(new CommentMention(this, u));
        }
    }
}