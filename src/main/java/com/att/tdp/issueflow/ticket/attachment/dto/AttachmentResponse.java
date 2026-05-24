package com.att.tdp.issueflow.ticket.attachment.dto;

import com.att.tdp.issueflow.ticket.attachment.Attachment;

/**
 * Metadata-only response for attachment endpoints. Does NOT include the
 * file bytes — those are served from the dedicated download endpoint
 * (GET /tickets/{tid}/attachments/{aid}/download or similar).
 *
 * Used both by the list endpoint and as the constructor target of the
 * JPQL projection query in AttachmentRepository.findMetadataByTicketId,
 * which explicitly avoids touching the BYTEA column.
 */
public record AttachmentResponse(
        Long id,
        String filename,
        String contentType,
        long sizeBytes,
        Long uploadedById
) {
    public static AttachmentResponse from(Attachment a) {
        return new AttachmentResponse(
                a.getId(),
                a.getFilename(),
                a.getContentType(),
                a.getSizeBytes(),
                a.getUploadedBy().getId()
        );
    }
}