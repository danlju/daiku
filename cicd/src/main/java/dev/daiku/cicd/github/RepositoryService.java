package dev.daiku.cicd.github;

import dev.daiku.cicd.builds.BuildStore;
import dev.daiku.core.port.ProjectRepository;
import dev.daiku.domain.project.Project;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RepositoryService {
  private final ProjectRepository projects;
  private final GitHubClient github;
  private final BuildStore builds;

  public RepositoryService(ProjectRepository projects, GitHubClient github, BuildStore builds) {
    this.projects = projects;
    this.github = github;
    this.builds = builds;
  }

  public Project connect(String input) {
    String repo =
        input
            .strip()
            .replaceFirst("^https://github.com/", "")
            .replaceFirst("/$", "")
            .replaceFirst("\\.git$", "");
    if (!repo.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")
        || repo.endsWith("/..")
        || repo.endsWith("/."))
      throw new IllegalArgumentException("Enter a GitHub repository as owner/repository");
    long installation = github.installation(repo).path("id").asLong();
    var metadata = github.repository(repo, github.token(installation));
    long repoId = metadata.path("id").asLong();
    Project existing = projects.byRepository(repoId, installation);
    if (existing != null) return existing;
    try {
      return projects.connect(
          repoId,
          installation,
          metadata.path("name").asText(),
          metadata.path("full_name").asText(),
          metadata.path("default_branch").asText());
    } catch (org.springframework.dao.DuplicateKeyException e) {
      existing = projects.byRepository(repoId, installation);
      if (existing != null) return existing;
      throw new IllegalStateException(
          "This repository is already connected to a different installation");
    }
  }

  public boolean sync(Project project) {
    if (!project.connected()) return true;
    try {
      var runs = github.runs(project.repository(), github.token(project.installationId()));
      for (var run : runs) builds.ingest(project, run);
      projects.synced(project.id());
      return true;
    } catch (RuntimeException e) {
      projects.failed(project.id());
      return false;
    }
  }

  @Scheduled(
      fixedDelayString = "${daiku.github.poll-ms}",
      initialDelayString = "${daiku.github.poll-ms}")
  public void poll() {
    if (github.configured()) projects.all().stream().filter(Project::connected).forEach(this::sync);
  }
}
