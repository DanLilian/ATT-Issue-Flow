package com.att.tdp.issueflow.ticket.dependency;

import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.common.JpaConfig;
import com.att.tdp.issueflow.common.error.ConflictException;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import com.att.tdp.issueflow.ticket.dependency.dto.AddDependencyRequest;
import com.att.tdp.issueflow.ticket.dependency.dto.DependencyResponse;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice test for TicketDependencyService. Focus areas:
 *   - self-block, missing-ticket, cross-project, duplicate, cycle rejection
 *   - direct and indirect cycle detection
 *   - cross-project blocking rejection
 *   - listing and removing
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketDependencyService.class, AuditService.class, JpaConfig.class,
         ObjectMapper.class})
class TicketDependencyServiceTest {

    @Autowired TicketDependencyService dependencyService;
    @Autowired TicketDependencyRepository dependencyRepository;
    @Autowired TicketRepository ticketRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired UserRepository userRepository;
    @PersistenceContext EntityManager entityManager;

    private Long projectAId;
    private Long projectBId;
    private Long ticketA1Id;
    private Long ticketA2Id;
    private Long ticketA3Id;
    private Long ticketB1Id;

    @BeforeEach
    void seedFixture() {
        User owner = userRepository.save(new User(
                "owner", "owner@x.com", "h", "Owner", UserRole.ADMIN));

        Project projectA = projectRepository.save(new Project("PA", "d", owner));
        Project projectB = projectRepository.save(new Project("PB", "d", owner));

        Ticket a1 = ticketRepository.save(makeTicket("A1", projectA));
        Ticket a2 = ticketRepository.save(makeTicket("A2", projectA));
        Ticket a3 = ticketRepository.save(makeTicket("A3", projectA));
        Ticket b1 = ticketRepository.save(makeTicket("B1", projectB));

        this.projectAId = projectA.getId();
        this.projectBId = projectB.getId();
        this.ticketA1Id = a1.getId();
        this.ticketA2Id = a2.getId();
        this.ticketA3Id = a3.getId();
        this.ticketB1Id = b1.getId();
    }

    private Ticket makeTicket(String title, Project project) {
        return new Ticket(title, "desc",
                TicketStatus.TODO, TicketPriority.LOW, TicketType.BUG,
                project, null, null);
    }

    // ─── add ────────────────────────────────────────────────────────────

    @Test
    void add_persistsDependency_betweenTicketsInSameProject() {
        DependencyResponse result = dependencyService.add(
                ticketA1Id, new AddDependencyRequest(ticketA2Id));

        assertThat(result.blockerId()).isEqualTo(ticketA2Id);
        assertThat(result.blockerStatus()).isEqualTo(TicketStatus.TODO);
        assertThat(dependencyRepository.existsByIdTicketIdAndIdBlockerTicketId(
                ticketA1Id, ticketA2Id)).isTrue();
    }

    @Test
    void add_rejectsSelfBlock() {
        assertThatThrownBy(() -> dependencyService.add(
                ticketA1Id, new AddDependencyRequest(ticketA1Id)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("itself");
    }

    @Test
    void add_throwsNotFound_whenTicketMissing() {
        assertThatThrownBy(() -> dependencyService.add(
                99_999L, new AddDependencyRequest(ticketA1Id)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void add_throwsNotFound_whenBlockerMissing() {
        assertThatThrownBy(() -> dependencyService.add(
                ticketA1Id, new AddDependencyRequest(99_999L)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void add_rejectsCrossProjectBlocking() {
        assertThatThrownBy(() -> dependencyService.add(
                ticketA1Id, new AddDependencyRequest(ticketB1Id)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("same project");
    }

    @Test
    void add_rejectsDuplicateDependency() {
        dependencyService.add(ticketA1Id, new AddDependencyRequest(ticketA2Id));

        assertThatThrownBy(() -> dependencyService.add(
                ticketA1Id, new AddDependencyRequest(ticketA2Id)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already");
    }

    // ─── cycle detection ────────────────────────────────────────────────

    @Test
    void add_rejectsDirectCycle() {
        // A1 blocked by A2; now try A2 blocked by A1 → cycle.
        dependencyService.add(ticketA1Id, new AddDependencyRequest(ticketA2Id));

        assertThatThrownBy(() -> dependencyService.add(
                ticketA2Id, new AddDependencyRequest(ticketA1Id)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void add_rejectsIndirectCycle() {
        // A1 blocked by A2; A2 blocked by A3; now A3 blocked by A1 → cycle.
        dependencyService.add(ticketA1Id, new AddDependencyRequest(ticketA2Id));
        dependencyService.add(ticketA2Id, new AddDependencyRequest(ticketA3Id));

        assertThatThrownBy(() -> dependencyService.add(
                ticketA3Id, new AddDependencyRequest(ticketA1Id)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cycle");
    }

    // ─── listing ────────────────────────────────────────────────────────

    @Test
    void findByTicket_returnsAllBlockers() {
        dependencyService.add(ticketA1Id, new AddDependencyRequest(ticketA2Id));
        dependencyService.add(ticketA1Id, new AddDependencyRequest(ticketA3Id));

        List<DependencyResponse> result = dependencyService.findByTicket(ticketA1Id);

        assertThat(result).hasSize(2)
                .extracting(DependencyResponse::blockerId)
                .containsExactlyInAnyOrder(ticketA2Id, ticketA3Id);
    }

    @Test
    void findByTicket_throwsNotFound_forMissingTicket() {
        assertThatThrownBy(() -> dependencyService.findByTicket(99_999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void findByTicket_returnsEmpty_whenNoBlockers() {
        assertThat(dependencyService.findByTicket(ticketA1Id)).isEmpty();
    }

    // ─── remove ─────────────────────────────────────────────────────────

    @Test
    void remove_deletesDependency() {
        dependencyService.add(ticketA1Id, new AddDependencyRequest(ticketA2Id));

        dependencyService.remove(ticketA1Id, ticketA2Id);

        assertThat(dependencyRepository.existsByIdTicketIdAndIdBlockerTicketId(
                ticketA1Id, ticketA2Id)).isFalse();
    }

    @Test
    void remove_throwsNotFound_whenDependencyMissing() {
        assertThatThrownBy(() -> dependencyService.remove(ticketA1Id, ticketA2Id))
                .isInstanceOf(NotFoundException.class);
    }
}