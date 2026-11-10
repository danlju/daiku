package dev.daiku.cicd.github;

import dev.daiku.cicd.builds.BuildStore;
import dev.daiku.core.ApplicationException;
import dev.daiku.core.port.ProjectRepository;
import dev.daiku.domain.project.Project;
import dev.daiku.domain.project.PullRequest;
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

  private record RepositoryDetails(
      long id, long installationId, String name, String fullName, String branch) {}

  public Project connect(long projectId, String input) {
    var project = projects.get(projectId);
    if (project.archived())
      throw new ApplicationException(
          ApplicationException.Kind.CONFLICT,
          "Restore this project before connecting a repository.");
    if (project.connected())
      throw new ApplicationException(
          ApplicationException.Kind.CONFLICT,
          "This project is already connected to a repository. Reload its board.");
    var details = discover(input);
    return projects.attachRepository(
        projectId, details.id(), details.installationId(), details.fullName(), details.branch());
  }

  private RepositoryDetails discover(String input) {
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
    if (installation <= 0
        || repoId <= 0
        || metadata.path("full_name").asText().isBlank()
        || metadata.path("name").asText().isBlank()
        || metadata.path("default_branch").asText().isBlank())
      throw new GitHubAccessException(
          "GitHub returned incomplete repository details. Retry connecting later.");
    return new RepositoryDetails(
        repoId,
        installation,
        metadata.path("name").asText(),
        metadata.path("full_name").asText(),
        metadata.path("default_branch").asText());
  }

  public Project connect(String input) {
    var details = discover(input);
    long repoId = details.id();
    long installation = details.installationId();
    Project existing = projects.byRepository(repoId, installation);
    if (existing != null) return existing;
    try {
      return projects.connect(
          repoId, installation, details.name(), details.fullName(), details.branch());
    } catch (org.springframework.dao.DuplicateKeyException e) {
      existing = projects.byRepository(repoId, installation);
      if (existing != null) return existing;
      throw new IllegalStateException(
          "This repository is already connected to a different installation");
    }
  }

  public boolean sync(Project project) {
    project = projects.get(project.id());
    if (project.archived()) return false;
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

  public boolean syncTicket(Project project, String branch, PullRequest pullRequest) {
    project = projects.get(project.id());
    if (!project.connected() || project.archived()) return false;
    try {
      String token = github.token(project.installationId());
      if (pullRequest != null) {
        for (var run : github.runsForCommit(project.repository(), token, pullRequest.headSha()))
          builds.ingest(project, run);
        if (!pullRequest.mergeSha().isEmpty()
            && !pullRequest.mergeSha().equals(pullRequest.headSha()))
          for (var run : github.runsForCommit(project.repository(), token, pullRequest.mergeSha()))
            builds.ingest(project, run);
      } else if (!branch.isEmpty()) {
        for (var run : github.runsForBranch(project.repository(), token, branch))
          builds.ingest(project, run);
      }
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
    if (github.configured())
      projects.all().stream()
          .filter(p -> !p.archived())
          .filter(Project::connected)
          .forEach(this::sync);
  }
}
