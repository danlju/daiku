package dev.daiku.persistence;

import static dev.daiku.core.ApplicationException.Kind.CONFLICT;
import static dev.daiku.core.ApplicationException.Kind.NOT_FOUND;

import dev.daiku.core.ApplicationException;
import dev.daiku.core.port.ProjectRepository;
import dev.daiku.domain.project.Project;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectRepository implements ProjectRepository {
  private final JdbcTemplate db;
  private final RowMapper<Project> mapper =
      (rs, n) ->
          new Project(
              rs.getLong("id"),
              rs.getObject("repository_id", Long.class),
              rs.getObject("installation_id", Long.class),
              rs.getString("name"),
              rs.getString("repository"),
              rs.getString("default_branch"),
              rs.getObject("synced_at", java.time.OffsetDateTime.class),
              rs.getString("sync_error"),
              rs.getBoolean("archived"));

  public JdbcProjectRepository(JdbcTemplate db) {
    this.db = db;
  }

  @Override
  public List<Project> all() {
    return db.query("SELECT * FROM projects ORDER BY created_at, id", mapper);
  }

  @Override
  public Project get(long id) {
    return db.query("SELECT * FROM projects WHERE id = ?", mapper, id).stream()
        .findFirst()
        .orElseThrow(() -> new ApplicationException(NOT_FOUND, "Project not found"));
  }

  @Override
  public void setArchived(long id, boolean archived) {
    if (db.update("UPDATE projects SET archived=? WHERE id=?", archived, id) == 0)
      throw new ApplicationException(NOT_FOUND, "Project not found");
  }

  @Override
  public Project createLocal(String name) {
    var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
    db.update(
        c -> {
          var ps = c.prepareStatement("INSERT INTO projects(name) VALUES (?)", new String[] {"id"});
          ps.setString(1, name);
          return ps;
        },
        key);
    return get(key.getKey().longValue());
  }

  @Override
  public Project connect(
      long repositoryId, long installationId, String name, String repository, String branch) {
    db.update(
        "INSERT INTO projects(repository_id, installation_id, name, repository, default_branch)"
            + " VALUES (?,?,?,?,?)",
        repositoryId,
        installationId,
        name,
        repository,
        branch);
    return byRepository(repositoryId, installationId);
  }

  @Override
  public Project attachRepository(
      long projectId, long repositoryId, long installationId, String repository, String branch) {
    int changed;
    try {
      changed =
          db.update(
              "UPDATE projects SET repository_id=?,installation_id=?,repository=?,default_branch=?"
                  + " WHERE id=? AND repository_id IS NULL AND archived=FALSE",
              repositoryId,
              installationId,
              repository,
              branch,
              projectId);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new ApplicationException(
          CONFLICT,
          "This repository is already connected to another project, possibly an archived one. Open"
              + " or restore that project, or choose a different repository.");
    }
    if (changed == 0) {
      var project = get(projectId);
      throw new ApplicationException(
          CONFLICT,
          project.archived()
              ? "Restore this project before connecting a repository."
              : "This project is already connected to a repository. Reload its board.");
    }
    return get(projectId);
  }

  @Override
  public Project byRepository(long repositoryId, long installationId) {
    return db
        .query(
            "SELECT * FROM projects WHERE repository_id=? AND installation_id=?",
            mapper,
            repositoryId,
            installationId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public void synced(long id) {
    db.update("UPDATE projects SET synced_at=CURRENT_TIMESTAMP, sync_error=NULL WHERE id=?", id);
  }

  @Override
  public void failed(long id) {
    db.update(
        "UPDATE projects SET sync_error=? WHERE id=?",
        "GitHub refresh failed. Check App permissions and installation access, then retry.",
        id);
  }
}
