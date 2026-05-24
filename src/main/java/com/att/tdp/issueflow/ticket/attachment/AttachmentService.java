package com.att.tdp.issueflow.ticket.attachment;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.common.error.ValidationException;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.attachment.dto.AttachmentResponse;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operations on {@link Attachment}: multipart upload, metadata listing,
 * byte download, and delete.
 *
 * Validation enforced here (in order):
 *   1. File must be non-empty.
 *   2. File size must be <= 10 MB. (Spring's multipart filter is the
 *      primary defense; this service check is belt-and-braces.)
 *   3. Content type must be in the allowed whitelist — same set as
 *      V1__init.sql's ck_attachments_content_type constraint.
 *   4. Ticket and uploader must exist (soft-deleted tickets fail
 *      naturally via @SQLRestriction).
 *
 * Audit rows: ATTACHMENT_UPLOAD on create, ATTACHMENT_DELETE on remove.
 */
@Service
@Transactional(readOnly = true)
public class AttachmentService {

    /** Must match V1__init.sql's ck_attachments_content_type constraint. */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "application/pdf",
            "text/plain"
    );

    /** Belt-and-braces; primary enforcement is Spring's multipart config. */
    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L;

    private final AttachmentRepository attachmentRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             TicketRepository ticketRepository,
                             UserRepository userRepository,
                             AuditService auditService) {
        this.attachmentRepository = attachmentRepository;
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    public List<AttachmentResponse> findByTicket(Long ticketId) {
        if (ticketRepository.findById(ticketId).isEmpty()) {
            throw NotFoundException.of("Ticket", ticketId);
        }
        return attachmentRepository.findMetadataByTicketId(ticketId);
    }

    /**
     * Returns the full attachment entity including its bytes. Caller is
     * responsible for HTTP shape (Content-Type, Content-Disposition).
     */
    public Attachment download(Long ticketId, Long attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> NotFoundException.of("Attachment", attachmentId));

        // Path / data consistency check — same pattern as comments.
        if (!attachment.getTicket().getId().equals(ticketId)) {
            throw NotFoundException.of("Attachment", attachmentId);
        }
        return attachment;
    }

    @Transactional
    public AttachmentResponse upload(Long ticketId, Long uploaderId, MultipartFile file) {
        // Validation: empty file.
        if (file == null || file.isEmpty()) {
            throw new ValidationException("File is required and cannot be empty");
        }

        // Validation: size (belt-and-braces; Spring's filter is the primary).
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ValidationException(
                "File exceeds maximum size of 10 MB (was %d bytes)"
                    .formatted(file.getSize()));
        }

        // Validation: content type.
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ValidationException(
                "Content type not allowed: %s. Allowed: %s"
                    .formatted(contentType, ALLOWED_CONTENT_TYPES));
        }

        // Existence checks. @SQLRestriction filters soft-deleted tickets.
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> NotFoundException.of("Ticket", ticketId));

        User uploader = userRepository.findById(uploaderId)
                .orElseThrow(() -> NotFoundException.of("User", uploaderId));

        // Read bytes. With files capped at 10 MB this is acceptable; for
        // larger files we'd stream via InputStreamResource.
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new ConflictException("Failed to read uploaded file: " + ex.getMessage());
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "unnamed";
        }

        Attachment attachment = new Attachment(
                ticket, filename, contentType, file.getSize(), bytes, uploader);
        Attachment saved = attachmentRepository.save(attachment);

        auditService.record(
                AuditAction.ATTACHMENT_UPLOAD,
                AuditEntityType.ATTACHMENT,
                saved.getId(),
                Map.of("filename", filename, "sizeBytes", file.getSize()));

        return AttachmentResponse.from(saved);
    }

    @Transactional
    public void delete(Long ticketId, Long attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> NotFoundException.of("Attachment", attachmentId));

        if (!attachment.getTicket().getId().equals(ticketId)) {
            throw NotFoundException.of("Attachment", attachmentId);
        }

        attachmentRepository.delete(attachment);

        auditService.record(
                AuditAction.ATTACHMENT_DELETE,
                AuditEntityType.ATTACHMENT,
                attachmentId,
                null);
    }
}