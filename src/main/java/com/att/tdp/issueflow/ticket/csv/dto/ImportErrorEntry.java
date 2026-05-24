package com.att.tdp.issueflow.ticket.csv.dto;

/**
 * One failed row in a CSV import. The {@code row} value is the
 * 1-based record number from commons-csv (not the raw file line
 * number, which differs when fields contain embedded newlines).
 */
public record ImportErrorEntry(long row, String reason) {}