package com.att.tdp.issueflow.ticket.csv;

import com.att.tdp.issueflow.ticket.csv.dto.ImportResultResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * REST controller for CSV import and export, mounted on /tickets
 * per the README contract:
 *
 *   GET  /tickets/export?projectId={pid}
 *   POST /tickets/import  (multipart: file + projectId)
 *
 * Auth: any authenticated user (no role restriction). If the assignment
 * later requires ADMIN-only, swap @PreAuthorize at the method level.
 */
@RestController
@RequestMapping("/tickets")
public class TicketCsvController {

    private final TicketCsvService csvService;

    public TicketCsvController(TicketCsvService csvService) {
        this.csvService = csvService;
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestParam("projectId") Long projectId) {
        byte[] csv = csvService.export(projectId);

        ContentDisposition cd = ContentDisposition.attachment()
                .filename("tickets-project-%d.csv".formatted(projectId), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(csv.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(csv);
    }

    @PostMapping("/import")
    public ImportResultResponse importCsv(
            @RequestParam("projectId") Long projectId,
            @RequestParam("file") MultipartFile file) {
        return csvService.importCsv(projectId, file);
    }
}