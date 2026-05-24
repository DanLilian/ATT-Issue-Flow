package com.att.tdp.issueflow.ticket.csv.dto;

import java.util.List;

/**
 * Response body for POST /tickets/import.
 * Partial-failure semantics: created tickets persist even if some rows
 * fail. The errors array enumerates the failed rows with the reason.
 */
public record ImportResultResponse(
        int created,
        int failed,
        List<ImportErrorEntry> errors
) {}