package dev.daiku.board;

import static dev.daiku.core.ApplicationException.Kind.*;

import dev.daiku.core.ApplicationException;
import dev.daiku.core.port.ProjectRepository;
import dev.daiku.domain.ticket.Ticket;
import dev.daiku.domain.ticket.TicketStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {
  private final TicketRepository tickets;

  private final ProjectRepository projects;

  public TicketService(TicketRepository tickets, ProjectRepository projects) {
    this.tickets = tickets;
    this.projects = projects;
  }

  private void requireActive(long projectId) {
    if (projects.get(projectId).archived())
      throw new ApplicationException(CONFLICT, "Restore this project before changing its tickets.");
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
    requireActive(projectId);
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
    requireActive(before.projectId());
    title = required(title, 200, "Title");
    description = text(description, 20000, "Description");
    criteria = text(criteria, 10000, "Acceptance criteria");
    priority(priority);
    if (status == null) throw new ApplicationException(INVALID_INPUT, "Choose a status");
    boolean changed =
        tickets.update(
            new Ticket(
                id,
                before.projectId(),
                title,
                description,
                criteria,
                priority,
                status,
                version,
                before.branch(),
                before.pullRequestNumber()));
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
  public void linkDevelopment(long id, long version, String branch, String pullRequest) {
    var before = get(id);
    requireActive(before.projectId());
    var project = projects.get(before.projectId());
    if (!project.connected())
      throw new ApplicationException(
          CONFLICT, "Connect this project to GitHub before linking development work.");
    branch = text(branch, 255, "Branch");
    if (!branch.isEmpty()) {
      boolean invalid =
          branch.equals("@")
              || branch.startsWith("-")
              || branch.startsWith("/")
              || branch.endsWith("/")
              || branch.endsWith(".")
              || branch.contains("..")
              || branch.contains("@{")
              || branch.contains("//")
              || branch.chars().anyMatch(c -> c <= 32 || c == 127 || "~^:?*[\\".indexOf(c) >= 0);
      for (String part : branch.split("/"))
        invalid |= part.startsWith(".") || part.endsWith(".lock");
      if (invalid)
        throw new ApplicationException(
            INVALID_INPUT, "Enter a valid Git branch name, such as feature/ticket-123.");
    }
    String input = text(pullRequest, 300, "Pull request");
    Long number = null;
    if (!input.isEmpty()) {
      String prefix = "https://github.com/" + project.repository() + "/pull/";
      if (input.regionMatches(true, 0, prefix, 0, prefix.length())) {
        input = input.substring(prefix.length()).replaceFirst("/$", "");
      } else if (input.startsWith("#")) {
        input = input.substring(1);
      }
      try {
        if (!input.matches("[1-9][0-9]*")) throw new NumberFormatException();
        number = Long.valueOf(input);
      } catch (NumberFormatException e) {
        throw new ApplicationException(
            INVALID_INPUT,
            "Enter a positive PR number or a pull request URL from this project's repository.");
      }
    }
    if (!tickets.update(
        new Ticket(
            id,
            before.projectId(),
            before.title(),
            before.description(),
            before.acceptanceCriteria(),
            before.priority(),
            before.status(),
            version,
            branch,
            number)))
      throw new ApplicationException(
          CONFLICT, "This ticket changed in another tab. Reload before saving development links.");
    tickets.event(
        id,
        "Development links updated by you: branch "
            + (branch.isEmpty() ? "none" : branch)
            + "; PR "
            + (number == null ? "none" : "#" + number));
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
    requireActive(ticket.projectId());
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
