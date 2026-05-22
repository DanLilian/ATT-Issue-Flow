package com.att.tdp.issueflow.ticket.attachment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByTicketIdOrderByCreatedAtDesc(Long ticketId);
}