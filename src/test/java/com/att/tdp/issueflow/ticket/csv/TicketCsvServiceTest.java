package com.att.tdp.issueflow.ticket.csv;

import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.common.JpaConfig;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.common.error.ValidationException;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.TicketService;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import com.att.tdp.issueflow.ticket.csv.dto.ImportResultResponse;
import com.att.tdp.issueflow.ticket.dependency.TicketDependencyRepository;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice test for TicketCsvService. Focus areas:
 *   - export emits header + one record per ticket
 *   - export quotes fields with embedded commas, quotes, newlines
 *   - round-trip: export -> import yields equivalent tickets
 *   - import partial failure: bad rows reported, good rows persist
 *   - import header validation: missing column rejects the whole file
 *   - import empty file rejected
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketCsvService.class, TicketService.class, AuditService.class,
         JpaConfig.class, ObjectMapper.class})
class TicketCsvServiceTest {

    @Autowired TicketCsvService csvService;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @Autowired TicketDependencyRepository dependencyRepository;
    @PersistenceContext EntityManager entityManager;

    private Long projectId;
    private Long assigneeId;

    @BeforeEach
    void seedFixture() {
        User owner = userRepository.save(new User(
                "owner", "owner@x.com", "h", "Owner", UserRole.ADMIN));
        User dev = userRepository.save(new User(
                "dev", "dev@x.com", "h", "Dev", UserRole.DEVELOPER));

        Project project = projectRepository.save(new Project("P", "d", owner));

        this.projectId = project.getId();
        this.assigneeId = dev.getId();
    }

    private MultipartFile csvFile(String content) {
        return new MockMultipartFile(
                "file", "tickets.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    // ─── export ─────────────────────────────────────────────────────────

    @Test
    void export_emitsHeader_andOneRowPerTicket() {
        Project project = projectRepository.findById(projectId).orElseThrow();
        ticketRepository.save(new Ticket("A", "desc A",
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                project, null, null));
        ticketRepository.save(new Ticket("B", "desc B",
                TicketStatus.IN_PROGRESS, TicketPriority.HIGH, TicketType.FEATURE,
                project, null, null));

        String csv = new String(csvService.export(projectId), StandardCharsets.UTF_8);
        String[] lines = csv.split("\\r?\\n");

        assertThat(lines).hasSizeGreaterThanOrEqualTo(3); // header + 2
        assertThat(lines[0]).contains("id,title,description,status,priority,type,assigneeId");
    }

    @Test
    void export_quotesFieldsWithEmbeddedCommasAndQuotes() {
        Project project = projectRepository.findById(projectId).orElseThrow();
        ticketRepository.save(new Ticket(
                "Bug, \"critical\" issue",          // comma + quotes in title
                "Line 1\nLine 2",                    // embedded newline
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                project, null, null));

        String csv = new String(csvService.export(projectId), StandardCharsets.UTF_8);

        // commons-csv quotes the title and doubles internal quotes:
        // "Bug, ""critical"" issue"
        assertThat(csv).contains("\"Bug, \"\"critical\"\" issue\"");
        // newline-containing description is quoted
        assertThat(csv).contains("\"Line 1\nLine 2\"");
    }

    @Test
    void export_throwsNotFound_whenProjectMissing() {
        assertThatThrownBy(() -> csvService.export(99_999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void export_emitsHeaderOnly_whenNoTickets() {
        String csv = new String(csvService.export(projectId), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("id,title,description,status,priority,type,assigneeId");
        // Only the header line plus terminator — no data records.
        String[] lines = csv.split("\\r?\\n");
        assertThat(lines).hasSize(1);
    }

    // ─── round-trip ─────────────────────────────────────────────────────

    @Test
    void roundTrip_exportThenImport_yieldsEquivalentTickets() {
        Project project = projectRepository.findById(projectId).orElseThrow();
        ticketRepository.save(new Ticket(
                "Bug, with comma",
                "Has \"quotes\" and\nnewline",
                TicketStatus.IN_PROGRESS, TicketPriority.HIGH, TicketType.BUG,
                project, null, null));
        entityManager.flush();

        // Export.
        byte[] csv = csvService.export(projectId);
        entityManager.clear();

        // Import into the same project.
        ImportResultResponse result = csvService.importCsv(
                projectId, new MockMultipartFile(
                    "file", "x.csv", "text/csv", csv));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(0);

        // Now there are two tickets in the project (original + import).
        List<Ticket> all = ticketRepository
                .findByProjectIdOrderByCreatedAtDesc(projectId);
        assertThat(all).hasSize(2);

        // Find the imported ticket (the new one) and verify its content
        // matches the original despite the comma, quotes, and newline.
        Ticket imported = all.stream()
                .filter(t -> t.getDescription() != null
                          && t.getDescription().contains("newline"))
                .findFirst().orElseThrow();
        assertThat(imported.getTitle()).isEqualTo("Bug, with comma");
        assertThat(imported.getDescription()).isEqualTo("Has \"quotes\" and\nnewline");
        assertThat(imported.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(imported.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(imported.getType()).isEqualTo(TicketType.BUG);
    }

    // ─── import ─────────────────────────────────────────────────────────

    @Test
    void import_persistsAllRows_whenAllValid() {
        String csv = """
            title,description,status,priority,type,assigneeId
            T1,desc 1,TODO,LOW,BUG,
            T2,desc 2,IN_PROGRESS,HIGH,FEATURE,
            """;

        ImportResultResponse result = csvService.importCsv(projectId, csvFile(csv));

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void import_partialFailure_keepsGoodRows_reportsBadRows() {
        String csv = """
            title,description,status,priority,type,assigneeId
            Good 1,d1,TODO,LOW,BUG,
            ,d2,TODO,LOW,BUG,
            Good 2,d3,WIZARD,LOW,BUG,
            Good 3,d4,TODO,LOW,BUG,
            """;
        // Row 2: missing title.
        // Row 3: invalid status enum.
        // Rows 1 and 4: succeed.

        ImportResultResponse result = csvService.importCsv(projectId, csvFile(csv));

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.errors()).hasSize(2)
                .extracting(e -> e.row())
                .containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    void import_rejectsWholeFile_whenHeaderMissingRequiredColumn() {
        String csv = """
            title,description,priority,type,assigneeId
            T1,d1,LOW,BUG,
            """;
        // Missing 'status' column.

        assertThatThrownBy(() -> csvService.importCsv(projectId, csvFile(csv)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("status");
    }

    @Test
    void import_rejectsEmptyFile() {
        MultipartFile empty = new MockMultipartFile(
                "file", "empty.csv", "text/csv", new byte[0]);

        assertThatThrownBy(() -> csvService.importCsv(projectId, empty))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void import_acceptsHeaderOnlyFile() {
        String csv = "title,description,status,priority,type,assigneeId\n";

        ImportResultResponse result = csvService.importCsv(projectId, csvFile(csv));

        assertThat(result.created()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    void import_assignsTickets_whenAssigneeIdProvided() {
        String csv = "title,description,status,priority,type,assigneeId\n"
                   + "T1,d,TODO,LOW,BUG,%d\n".formatted(assigneeId);

        ImportResultResponse result = csvService.importCsv(projectId, csvFile(csv));

        assertThat(result.created()).isEqualTo(1);

        List<Ticket> tickets = ticketRepository
                .findByProjectIdOrderByCreatedAtDesc(projectId);
        Ticket t = tickets.get(0);
        assertThat(t.getAssignee()).isNotNull();
        assertThat(t.getAssignee().getId()).isEqualTo(assigneeId);
    }

    @Test
    void import_throwsNotFound_whenProjectMissing() {
        String csv = "title,description,status,priority,type,assigneeId\nT,d,TODO,LOW,BUG,\n";

        assertThatThrownBy(() -> csvService.importCsv(99_999L, csvFile(csv)))
                .isInstanceOf(NotFoundException.class);
    }
}