package dev.daiku.board;

import static dev.daiku.core.ApplicationException.Kind.*;

import dev.daiku.core.ApplicationException;
import dev.daiku.domain.ticket.Ticket;
import dev.daiku.domain.ticket.TicketStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {
  private final TicketRepository tickets;

  public TicketService(TicketRepository tickets) {
    this.tickets = tickets;
  }

  public List<Ticket> forProject(long id) {
    return tickets.forProject(id);
  }

  public Ticket get(long id) {
    return tickets.get(id);
  }

  @Transactional
  public long create(
      long projectId, String title, String description, String criteria, String priority) {
    title = required(title, 200, "Title");
    description = text(description, 20000, "Description");
    criteria = text(criteria, 10000, "Acceptance criteria");
    priority(priority);
    long id = tickets.create(projectId, title, description, criteria, priority);
    tickets.event(id, "Ticket created by you");
    return id;
  }

  @Transactional
  public void update(
      long id,
      long version,
      String title,
      String description,
      String criteria,
      String priority,
      TicketStatus status) {
    Ticket before = get(id);
    title = required(title, 200, "Title");
    description = text(description, 20000, "Description");
    criteria = text(criteria, 10000, "Acceptance criteria");
    priority(priority);
    if (status == null) throw new ApplicationException(INVALID_INPUT, "Choose a status");
    boolean changed =
        tickets.update(
            new Ticket(
                id, before.projectId(), title, description, criteria, priority, status, version));
    if (!changed)
      throw new ApplicationException(
          CONFLICT, "This ticket changed in another tab. Reload before saving.");
    tickets.event(
        id,
        before.status() == status
            ? "Ticket updated by you"
            : "Moved from " + before.status().getLabel() + " to " + status.getLabel() + " by you");
  }

  @Transactional
  public Ticket move(long id, long version, TicketStatus status) {
    Ticket before = get(id);
    update(
        id,
        version,
        before.title(),
        before.description(),
        before.acceptanceCriteria(),
        before.priority(),
        status);
    return get(id);
  }

  @Transactional
  public long delete(long id, long version) {
    Ticket ticket = get(id);
    if (!tickets.delete(id, version))
      throw new ApplicationException(
          CONFLICT, "This ticket changed in another tab. Reload before deleting.");
    return ticket.projectId();
  }

  public List<String> history(long id) {
    return tickets.history(id);
  }

  private void priority(String value) {
    if (!List.of("LOW", "MEDIUM", "HIGH").contains(value))
      throw new ApplicationException(INVALID_INPUT, "Choose a valid priority");
  }

  public static String required(String value, int max, String field) {
    String cleaned = text(value, max, field);
    if (cleaned.isBlank()) throw new ApplicationException(INVALID_INPUT, field + " is required");
    return cleaned;
  }

  private static String text(String value, int max, String field) {
    String cleaned = value == null ? "" : value.strip();
    if (cleaned.length() > max)
      throw new ApplicationException(INVALID_INPUT, field + " is too long");
    return cleaned;
  }
}
