package dev.daiku.board.persistence;

import static dev.daiku.core.ApplicationException.Kind.NOT_FOUND;

import dev.daiku.board.TicketRepository;
import dev.daiku.core.ApplicationException;
import dev.daiku.core.port.TicketLinks;
import dev.daiku.domain.ticket.Ticket;
import dev.daiku.domain.ticket.TicketStatus;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTicketRepository implements TicketRepository, TicketLinks {
  private final JdbcTemplate db;
  private final RowMapper<Ticket> mapper =
      (rs, n) ->
          new Ticket(
              rs.getLong("id"),
              rs.getLong("project_id"),
              rs.getString("title"),
              rs.getString("description"),
              rs.getString("acceptance_criteria"),
              rs.getString("priority"),
              TicketStatus.valueOf(rs.getString("status")),
              rs.getLong("version"),
              rs.getString("branch"),
              rs.getObject("pull_request_number", Long.class));

  public JdbcTicketRepository(JdbcTemplate db) {
    this.db = db;
  }

  @Override
  public List<Ticket> forProject(long id) {
    return db.query(
        "SELECT * FROM tickets WHERE project_id=? ORDER BY created_at DESC, id DESC", mapper, id);
  }

  @Override
  public List<Long> pullRequestsForProject(long projectId) {
    return db.queryForList(
        "SELECT DISTINCT pull_request_number FROM tickets WHERE project_id=? AND"
            + " pull_request_number IS NOT NULL ORDER BY pull_request_number",
        Long.class,
        projectId);
  }

  @Override
  public Ticket get(long id) {
    return db.query("SELECT * FROM tickets WHERE id=?", mapper, id).stream()
        .findFirst()
        .orElseThrow(() -> new ApplicationException(NOT_FOUND, "Ticket not found"));
  }

  @Override
  public long create(
      long projectId, String title, String description, String criteria, String priority) {
    var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
    db.update(
        c -> {
          var ps =
              c.prepareStatement(
                  "INSERT INTO"
                      + " tickets(project_id,title,description,acceptance_criteria,priority,status)"
                      + " VALUES (?,?,?,?,?,'SUGGESTED')",
                  new String[] {"id"});
          ps.setLong(1, projectId);
          ps.setString(2, title);
          ps.setString(3, description);
          ps.setString(4, criteria);
          ps.setString(5, priority);
          return ps;
        },
        key);
    return key.getKey().longValue();
  }

  @Override
  public boolean update(Ticket ticket) {
    int changed =
        db.update(
            "UPDATE tickets SET"
                + " title=?,description=?,acceptance_criteria=?,priority=?,status=?,branch=?,pull_request_number=?,version=version+1,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=? AND version=?",
            ticket.title(),
            ticket.description(),
            ticket.acceptanceCriteria(),
            ticket.priority(),
            ticket.status().name(),
            ticket.branch(),
            ticket.pullRequestNumber(),
            ticket.id(),
            ticket.version());
    return changed != 0;
  }

  @Override
  public boolean delete(long id, long version) {
    // Lock the ticket before removing history so concurrent edits cannot append new events.
    var matching =
        db.queryForList(
            "SELECT id FROM tickets WHERE id=? AND version=? FOR UPDATE", Long.class, id, version);
    if (matching.isEmpty()) return false;
    db.update("DELETE FROM ticket_events WHERE ticket_id=?", id);
    return db.update("DELETE FROM tickets WHERE id=? AND version=?", id, version) == 1;
  }

  @Override
  public List<String> history(long id) {
    return db.query(
        "SELECT detail FROM ticket_events WHERE ticket_id=? ORDER BY id DESC",
        (rs, n) -> rs.getString(1),
        id);
  }

  @Override
  public void event(long id, String detail) {
    db.update("INSERT INTO ticket_events(ticket_id,detail) VALUES (?,?)", id, detail);
  }
}
