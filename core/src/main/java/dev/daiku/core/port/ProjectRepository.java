package dev.daiku.core.port;

import dev.daiku.domain.project.Project;
import java.util.List;

/** Shared project persistence boundary used by the workspace and CI/CD features. */
public interface ProjectRepository {
  List<Project> all();

  Project get(long id);

  Project createLocal(String name);

  Project connect(
      long repositoryId, long installationId, String name, String repository, String branch);

  Project byRepository(long repositoryId, long installationId);

  void synced(long id);

  void failed(long id);
}
