package com.att.tdp.issueflow.ticket.attachment;

import com.att.tdp.issueflow.common.BaseEntity;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "attachments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attachment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * Raw bytes. @Lob + @Basic(LAZY) ensures the bytes are NOT loaded
     * when the row is fetched for listing — only when explicitly read.
     * Critical for performance: listing attachments should not pull MBs.
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "data", nullable = false)
    private byte[] data;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_id", nullable = false)
    private User uploadedBy;

    public Attachment(Ticket ticket, String filename, String contentType,
                      long sizeBytes, byte[] data, User uploadedBy) {
        this.ticket = ticket;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.data = data;
        this.uploadedBy = uploadedBy;
    }
}