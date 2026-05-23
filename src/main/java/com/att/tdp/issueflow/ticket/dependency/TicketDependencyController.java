package com.att.tdp.issueflow.ticket.dependency;

import com.att.tdp.issueflow.ticket.dependency.dto.AddDependencyRequest;
import com.att.tdp.issueflow.ticket.dependency.dto.DependencyResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for ticket dependencies. Mounted under
 * /tickets/{ticketId}/dependencies so the URL expresses the
 * parent-child relationship — matches the README contract.
 *
 *   GET    /tickets/{tid}/dependencies                returns blocker list
 *   POST   /tickets/{tid}/dependencies                body {blockedBy}, 200
 *   DELETE /tickets/{tid}/dependencies/{blockerId}    200, empty body
 */
@RestController
@RequestMapping("/tickets/{ticketId}/dependencies")
public class TicketDependencyController {

    private final TicketDependencyService dependencyService;

    public TicketDependencyController(TicketDependencyService dependencyService) {
        this.dependencyService = dependencyService;
    }

    @GetMapping
    public List<DependencyResponse> getByTicket(@PathVariable Long ticketId) {
        return dependencyService.findByTicket(ticketId);
    }

    @PostMapping
    public DependencyResponse add(@PathVariable Long ticketId,
                                  @Valid @RequestBody AddDependencyRequest req) {
        return dependencyService.add(ticketId, req);
    }

    @DeleteMapping("/{blockerId}")
    public ResponseEntity<Void> remove(@PathVariable Long ticketId,
                                       @PathVariable Long blockerId) {
        dependencyService.remove(ticketId, blockerId);
        return ResponseEntity.ok().build();
    }
}