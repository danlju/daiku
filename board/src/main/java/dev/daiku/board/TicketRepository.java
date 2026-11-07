package dev.daiku.board;

import dev.daiku.domain.ticket.Ticket;
import java.util.List;

public interface TicketRepository {
  List<Ticket> forProject(long projectId);

  Ticket get(long id);

  long create(long projectId, String title, String description, String criteria, String priority);

  /** Returns false if the expected version no longer matches. */
  boolean update(Ticket ticket);

  /** Deletes the ticket and its history; returns false if the version no longer matches. */
  boolean delete(long id, long version);

  List<String> history(long id);

  void event(long id, String detail);
}
