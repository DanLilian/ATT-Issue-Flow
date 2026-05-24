package com.att.tdp.issueflow.ticket.attachment;
import com.att.tdp.issueflow.ticket.attachment.dto.AttachmentResponse;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    /**
     * Metadata-only listing query — uses a JPQL constructor expression
     * to project just the columns we need, avoiding the BYTEA data column
     * entirely. This is the right pattern even if @Basic(LAZY) is honored:
     * the explicit projection is bytecode-enhancement-independent and
     * traceable in the SQL log.
     */
    @Query("""
        SELECT new com.att.tdp.issueflow.ticket.attachment.dto.AttachmentResponse(
            a.id, a.filename, a.contentType, a.sizeBytes, a.uploadedBy.id)
        FROM Attachment a
        WHERE a.ticket.id = :ticketId
        ORDER BY a.createdAt DESC
    """)
    List<AttachmentResponse> findMetadataByTicketId(@Param("ticketId") Long ticketId);
}