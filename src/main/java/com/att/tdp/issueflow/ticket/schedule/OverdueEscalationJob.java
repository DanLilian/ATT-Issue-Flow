package com.att.tdp.issueflow.ticket.schedule;

import com.att.tdp.issueflow.ticket.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic job that scans for overdue tickets and applies one escalation
 * step to each (LOW -> MEDIUM -> HIGH -> CRITICAL, then mark isOverdue).
 *
 * Schedule is configured via the property
 * {@code issueflow.schedule.overdue-escalation-cron} (default: every 15
 * minutes). Set to "-" to disable.
 *
 * The job itself is a thin wrapper: the real logic lives in
 * {@link TicketService#escalateAllOverdue()}, where the transactional
 * boundary and idempotency guarantees are established. Keeping the job
 * thin means we can unit-test the service without involving Spring's
 * scheduling machinery, and we can invoke escalation manually from
 * tests or admin tooling if ever needed.
 */
@Component
public class OverdueEscalationJob {

    private static final Logger log = LoggerFactory.getLogger(OverdueEscalationJob.class);

    private final TicketService ticketService;

    public OverdueEscalationJob(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Scheduled(cron = "${issueflow.schedule.overdue-escalation-cron}")
    public void run() {
        log.debug("Starting overdue-escalation scan");
        try {
            int escalated = ticketService.escalateAllOverdue();
            if (escalated > 0) {
                log.info("Overdue-escalation scan complete: {} tickets escalated", escalated);
            } else {
                log.debug("Overdue-escalation scan complete: no tickets escalated");
            }
        } catch (Exception ex) {
            // Catch to prevent a single bad scan from disabling future runs.
            // Spring's scheduler does suppress exceptions for fixed-rate/cron
            // jobs by default, but logging explicitly here gives us visibility.
            log.error("Overdue-escalation scan failed", ex);
        }
    }
}