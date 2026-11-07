package dev.daiku.cicd.builds;

import com.fasterxml.jackson.databind.JsonNode;
import dev.daiku.domain.build.BuildRun;
import dev.daiku.domain.project.Project;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BuildStore {
  private final JdbcTemplate db;

  public BuildStore(JdbcTemplate db) {
    this.db = db;
  }

  public List<BuildRun> recent(long projectId) {
    return db.query(
        "SELECT * FROM build_runs WHERE project_id=? ORDER BY updated_at DESC, id DESC LIMIT 15",
        (rs, n) ->
            new BuildRun(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("branch"),
                rs.getString("commit_sha"),
                rs.getString("status"),
                rs.getString("conclusion"),
                rs.getString("html_url"),
                rs.getLong("run_number"),
                rs.getObject("updated_at", OffsetDateTime.class)),
        projectId);
  }

  @Transactional
  public void ingest(Project project, JsonNode run) {
    long id = run.path("id").asLong();
    if (id <= 0 || run.path("updated_at").asText().isBlank())
      throw new IllegalArgumentException("Missing run identity");
    OffsetDateTime updated = OffsetDateTime.parse(run.path("updated_at").asText());
    long attempt = run.path("run_attempt").asLong(1);
    // A project row lock serializes polling and webhook updates, including first inserts.
    db.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", Long.class, project.id());
    var existing = db.queryForList("SELECT run_attempt, status FROM build_runs WHERE id=?", id);
    if (!existing.isEmpty()) {
      var row = existing.getFirst();
      long oldAttempt = ((Number) row.get("run_attempt")).longValue();
      OffsetDateTime oldTime =
          db.queryForObject(
              "SELECT updated_at FROM build_runs WHERE id=?", OffsetDateTime.class, id);
      if (attempt < oldAttempt || (attempt == oldAttempt && updated.isBefore(oldTime))) return;
      if (attempt == oldAttempt
          && updated.isEqual(oldTime)
          && rank(run.path("status").asText()) <= rank((String) row.get("status"))) return;
    }
    String url = run.path("html_url").asText();
    if (!url.startsWith("https://github.com/" + project.repository() + "/actions/runs/"))
      throw new IllegalArgumentException("Unexpected build URL");
    String conclusion = run.path("conclusion").isTextual() ? run.path("conclusion").asText() : null;
    if (existing.isEmpty()) {
      db.update(
          "INSERT INTO"
              + " build_runs(id,project_id,name,branch,commit_sha,status,conclusion,html_url,run_number,run_attempt,updated_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
          id,
          project.id(),
          run.path("name").asText("Workflow"),
          run.path("head_branch").asText(""),
          run.path("head_sha").asText(""),
          run.path("status").asText(),
          conclusion,
          url,
          run.path("run_number").asLong(),
          attempt,
          updated);
    } else {
      db.update(
          "UPDATE build_runs SET"
              + " name=?,branch=?,commit_sha=?,status=?,conclusion=?,html_url=?,run_attempt=?,updated_at=?"
              + " WHERE id=? AND project_id=?",
          run.path("name").asText("Workflow"),
          run.path("head_branch").asText(""),
          run.path("head_sha").asText(""),
          run.path("status").asText(),
          conclusion,
          url,
          attempt,
          updated,
          id,
          project.id());
    }
  }

  private static int rank(String status) {
    return switch (status) {
      case "completed" -> 3;
      case "in_progress" -> 2;
      default -> 1;
    };
  }
}
