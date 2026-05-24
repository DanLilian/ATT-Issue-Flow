package com.att.tdp.issueflow.ticket.csv;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.common.error.ValidationException;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.TicketService;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import com.att.tdp.issueflow.ticket.csv.dto.ImportErrorEntry;
import com.att.tdp.issueflow.ticket.csv.dto.ImportResultResponse;
import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CSV export and partial-failure import for tickets.
 *
 * Export columns (in order, per README): id, title, description, status,
 * priority, type, assigneeId. Empty cells render as empty strings;
 * description fields with embedded commas, quotes, or newlines are
 * properly quoted by commons-csv.
 *
 * Import semantics:
 *   - Whole file is rejected (ValidationException) if zero bytes or
 *     if the header is missing any of the required columns.
 *   - Each data row is processed independently. A failing row does
 *     NOT roll back successful rows; commits are per row.
 *   - Per-row transactionality is achieved via the @Transactional
 *     boundary on TicketService.create (called across the proxy).
 *   - The {@code id} column is ignored on import — new tickets get
 *     fresh ids. The exported id is informational only.
 */
@Service
public class TicketCsvService {

    /** Column header used by both export and import. */
    static final String[] CSV_COLUMNS = {
        "id", "title", "description", "status", "priority", "type", "assigneeId"
    };

    /** Required columns on import. {@code id} is optional — ignored if present. */
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
        "title", "description", "status", "priority", "type", "assigneeId"
    );

    private final TicketService ticketService;
    private final TicketRepository ticketRepository;
    private final ProjectRepository projectRepository;
    private final AuditService auditService;

    public TicketCsvService(TicketService ticketService,
                            TicketRepository ticketRepository,
                            ProjectRepository projectRepository,
                            AuditService auditService) {
        this.ticketService = ticketService;
        this.ticketRepository = ticketRepository;
        this.projectRepository = projectRepository;
        this.auditService = auditService;
    }

    // ─── export ─────────────────────────────────────────────────────────

    /**
     * Renders all tickets in {@code projectId} as CSV. Soft-deleted tickets
     * are excluded by @SQLRestriction on the entity. Returns the encoded
     * UTF-8 byte payload; the controller wraps it in the HTTP response with
     * Content-Type: text/csv and Content-Disposition: attachment.
     */
    @Transactional(readOnly = true)
    public byte[] export(Long projectId) {
        if (!projectRepository.findById(projectId).isPresent()) {
            throw NotFoundException.of("Project", projectId);
        }

        List<Ticket> tickets = ticketRepository
                .findByProjectIdOrderByCreatedAtDesc(projectId);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(CSV_COLUMNS)
                .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, format)) {

            for (Ticket t : tickets) {
                printer.printRecord(
                    t.getId(),
                    t.getTitle(),
                    nullSafe(t.getDescription()),
                    t.getStatus().name(),
                    t.getPriority().name(),
                    t.getType().name(),
                    t.getAssignee() == null ? "" : t.getAssignee().getId()
                );
            }
            printer.flush();
        } catch (IOException ex) {
            throw new RuntimeException("Failed to write CSV", ex);
        }

        return out.toByteArray();
    }

    // ─── import ─────────────────────────────────────────────────────────

    /**
     * Parses the CSV and creates one ticket per row. Failed rows are
     * collected with their record number and reason; successful rows
     * persist regardless.
     *
     * NOT @Transactional — per-row commits happen via TicketService.create's
     * own @Transactional boundary (proxy-call into a different bean).
     * Marking this method @Transactional would join the outer transaction
     * with each create, and a rollback-only on any row would roll back the
     * whole batch — defeating partial-failure semantics.
     */
    public ImportResultResponse importCsv(Long projectId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("CSV file is required and cannot be empty");
        }
        if (!projectRepository.findById(projectId).isPresent()) {
            throw NotFoundException.of("Project", projectId);
        }

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()                    // read header from first row
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();

        int created = 0;
        List<ImportErrorEntry> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                 new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = new CSVParser(reader, format)) {

            // Validate required header columns are present.
            Set<String> actualColumns = parser.getHeaderMap().keySet();
            List<String> missing = REQUIRED_COLUMNS.stream()
                    .filter(c -> !actualColumns.contains(c))
                    .sorted()
                    .toList();
            if (!missing.isEmpty()) {
                throw new ValidationException(
                    "CSV is missing required columns: " + missing);
            }

            for (CSVRecord record : parser) {
                long rowNumber = record.getRecordNumber();
                try {
                    CreateTicketRequest req = parseRow(record, projectId);
                    ticketService.create(req);
                    created++;
                } catch (Exception ex) {
                    errors.add(new ImportErrorEntry(rowNumber, ex.getMessage()));
                }
            }
        } catch (IOException ex) {
            throw new ValidationException("Failed to read CSV: " + ex.getMessage());
        }

        // Summary audit row. Per-ticket TICKET_CREATE rows are already
        // emitted by TicketService.create for each successful row.
        auditService.record(
                AuditAction.TICKET_IMPORT,
                AuditEntityType.PROJECT,
                projectId,
                Map.of("created", created,
                       "failed", errors.size()));

        return new ImportResultResponse(created, errors.size(), errors);
    }

    private CreateTicketRequest parseRow(CSVRecord record, Long projectId) {
        String title = record.get("title");
        String description = blankToNull(record.get("description"));
        TicketStatus status = parseEnum(TicketStatus.class, record.get("status"), "status");
        TicketPriority priority = parseEnum(TicketPriority.class, record.get("priority"), "priority");
        TicketType type = parseEnum(TicketType.class, record.get("type"), "type");
        Long assigneeId = parseOptionalLong(record.get("assigneeId"), "assigneeId");

        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }

        return new CreateTicketRequest(
        title, description, status, priority, type,
        projectId, assigneeId, null /* dueDate */);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "%s has invalid value '%s'".formatted(field, value));
        }
    }

    private static Long parseOptionalLong(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                "%s must be numeric or empty, was '%s'".formatted(field, value));
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}