package dev.daiku.cicd.github;

import com.fasterxml.jackson.databind.JsonNode;
import dev.daiku.domain.project.Project;
import dev.daiku.domain.project.PullRequest;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PullRequestStore {
  private final JdbcTemplate db;

  public PullRequestStore(JdbcTemplate db) {
    this.db = db;
  }

  public PullRequest get(long projectId, long number) {
    return db
        .query(
            "SELECT * FROM pull_requests WHERE project_id=? AND number=?",
            (rs, n) ->
                new PullRequest(
                    rs.getLong("number"),
                    rs.getString("title"),
                    rs.getString("state"),
                    rs.getBoolean("draft"),
                    rs.getBoolean("merged"),
                    rs.getString("head_branch"),
                    rs.getString("head_sha"),
                    rs.getString("merge_sha"),
                    rs.getObject("updated_at", OffsetDateTime.class),
                    rs.getObject("synced_at", OffsetDateTime.class),
                    rs.getString("sync_error")),
            projectId,
            number)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private boolean prepare(long projectId, long number) {
    if (Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT archived FROM projects WHERE id=? FOR UPDATE", Boolean.class, projectId)))
      return false;
    if (get(projectId, number) == null)
      db.update("INSERT INTO pull_requests(project_id,number) VALUES (?,?)", projectId, number);
    return true;
  }

  @Transactional
  public void save(Project project, long number, JsonNode node) {
    if (node.path("number").asLong() != number
        || node.path("base").path("repo").path("id").asLong() != project.repositoryId()
        || !(node.path("state").asText().equals("open")
            || node.path("state").asText().equals("closed"))
        || !node.path("head").path("sha").asText().matches("[0-9a-fA-F]{40,64}"))
      throw new GitHubAccessException(
          "GitHub returned incomplete or unexpected pull request details. Retry later.");
    String mergeSha = node.path("merge_commit_sha").asText("");
    if (!mergeSha.isEmpty() && !mergeSha.matches("[0-9a-fA-F]{40,64}"))
      throw new GitHubAccessException("GitHub returned an unexpected merge commit. Retry later.");
    var updated = OffsetDateTime.parse(node.path("updated_at").asText());
    if (!prepare(project.id(), number)) return;
    var old = get(project.id(), number);
    if (old.updatedAt() != null && updated.isBefore(old.updatedAt())) return;
    db.update(
        "UPDATE pull_requests SET"
            + " title=?,state=?,draft=?,merged=?,head_branch=?,head_sha=?,merge_sha=?,updated_at=?,synced_at=?,sync_error=NULL"
            + " WHERE project_id=? AND number=?",
        node.path("title").asText(),
        node.path("state").asText(),
        node.path("draft").asBoolean(),
        node.path("merged").asBoolean(),
        node.path("head").path("ref").asText(),
        node.path("head").path("sha").asText(),
        node.path("merge_commit_sha").asText(""),
        updated,
        OffsetDateTime.now(),
        project.id(),
        number);
  }

  @Transactional
  public void failed(long projectId, long number, String message) {
    if (prepare(projectId, number))
      db.update(
          "UPDATE pull_requests SET sync_error=? WHERE project_id=? AND number=?",
          message,
          projectId,
          number);
  }
}
