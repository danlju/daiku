package dev.daiku.domain.ticket;

public record Ticket(
    long id,
    long projectId,
    String title,
    String description,
    String acceptanceCriteria,
    String priority,
    TicketStatus status,
    long version) {}
