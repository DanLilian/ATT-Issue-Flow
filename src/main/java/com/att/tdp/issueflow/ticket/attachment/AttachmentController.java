package com.att.tdp.issueflow.ticket.attachment;

import com.att.tdp.issueflow.ticket.attachment.dto.AttachmentResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * REST controller for ticket attachments, mounted at
 * /tickets/{ticketId}/attachments per the README contract.
 *
 *   GET    /tickets/{tid}/attachments                       list (metadata)
 *   POST   /tickets/{tid}/attachments                       multipart upload
 *   GET    /tickets/{tid}/attachments/{aid}/download        byte download
 *   DELETE /tickets/{tid}/attachments/{aid}                 hard delete
 *
 * The dedicated /download path keeps the download semantically explicit
 * and lets the same metadata endpoint return a Location-style URL if
 * we ever want to add one.
 */
@RestController
@RequestMapping("/tickets/{ticketId}/attachments")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @GetMapping
    public List<AttachmentResponse> getByTicket(@PathVariable Long ticketId) {
        return attachmentService.findByTicket(ticketId);
    }

    /**
     * Multipart upload. Spring extracts the file from the 'file' form field
     * and the uploader id from the 'uploaderId' form field.
     *
     * If the file exceeds spring.servlet.multipart.max-file-size, Spring
     * throws MaxUploadSizeExceededException before this method runs;
     * GlobalExceptionHandler maps it to 413.
     */
    @PostMapping
    public AttachmentResponse upload(
            @PathVariable Long ticketId,
            @RequestParam("uploaderId") Long uploaderId,
            @RequestParam("file") MultipartFile file) {
        return attachmentService.upload(ticketId, uploaderId, file);
    }

    @GetMapping("/{attachmentId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long ticketId,
                                           @PathVariable Long attachmentId) {
        Attachment a = attachmentService.download(ticketId, attachmentId);

        ContentDisposition cd = ContentDisposition.attachment()
                .filename(a.getFilename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .contentLength(a.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(a.getData());
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable Long ticketId,
                                       @PathVariable Long attachmentId) {
        attachmentService.delete(ticketId, attachmentId);
        return ResponseEntity.ok().build();
    }
}