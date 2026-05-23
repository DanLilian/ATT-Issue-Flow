package com.att.tdp.issueflow.comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentMentionRepository extends JpaRepository<CommentMention, Long> {

    /**
     * Paginated mentions for a user, newest first.
     * Backs GET /users/{userId}/mentions with optional page/pageSize.
     *
     * The query returns Comments (not CommentMentions) so the response
     * DTO can carry the comment payload directly. The join through
     * mentions filters to comments where the user appears.
     */
    @Query("""
        SELECT c FROM Comment c
        JOIN c.mentions m
        WHERE m.mentionedUser.id = :userId
        ORDER BY c.createdAt DESC
        """)
    Page<Comment> findCommentsMentioningUser(@Param("userId") Long userId, Pageable pageable);
}