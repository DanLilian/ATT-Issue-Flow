package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.ticket.dto.CreateTicketRequest;
import com.att.tdp.issueflow.ticket.dto.TicketResponse;
import com.att.tdp.issueflow.ticket.dto.UpdateTicketRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for /tickets.
 *
 * Standard endpoints are open to any authenticated user. /tickets/deleted
 * and /tickets/{id}/restore are ADMIN-only via @PreAuthorize, reusing the
 * pattern established in Phase 6.
 *
 * Endpoint shapes match the README contract:
 *   GET    /tickets?projectId={id}      list by project
 *   GET    /tickets/{id}                one ticket
 *   POST   /tickets                     create, returns 200 (not 201)
 *   PATCH  /tickets/{id}                partial update, empty body
 *   DELETE /tickets/{id}                soft delete, empty body
 *   GET    /tickets/deleted?projectId   ADMIN only
 *   POST   /tickets/{id}/restore        ADMIN only
 */
@RestController
@RequestMapping("/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping
    public List<TicketResponse> getByProject(@RequestParam Long projectId) {
        return ticketService.findByProject(projectId);
    }

    @GetMapping("/{ticketId}")
    public TicketResponse getById(@PathVariable Long ticketId) {
        return ticketService.findById(ticketId);
    }

    @PostMapping
    public TicketResponse create(@Valid @RequestBody CreateTicketRequest req) {
        return ticketService.create(req);
    }

    @PatchMapping("/{ticketId}")
    public ResponseEntity<Void> update(@PathVariable Long ticketId,
                                       @Valid @RequestBody UpdateTicketRequest req) {
        ticketService.update(ticketId, req);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{ticketId}")
    public ResponseEntity<Void> softDelete(@PathVariable Long ticketId) {
        ticketService.softDelete(ticketId);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/deleted")
    public List<TicketResponse> getDeleted(@RequestParam Long projectId) {
        return ticketService.findAllDeletedByProject(projectId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{ticketId}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long ticketId) {
        ticketService.restore(ticketId);
        return ResponseEntity.ok().build();
    }
}