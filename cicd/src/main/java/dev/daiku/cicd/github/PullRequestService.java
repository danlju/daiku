package dev.daiku.cicd.github;

import dev.daiku.core.port.ProjectRepository;
import dev.daiku.core.port.TicketLinks;
import dev.daiku.domain.project.Project;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PullRequestService {
  private final ProjectRepository projects;
  private final TicketLinks links;
  private final GitHubClient github;
  private final PullRequestStore store;
  private final RepositoryService repositories;

  public PullRequestService(
      ProjectRepository projects,
      TicketLinks links,
      GitHubClient github,
      PullRequestStore store,
      RepositoryService repositories) {
    this.projects = projects;
    this.links = links;
    this.github = github;
    this.store = store;
    this.repositories = repositories;
  }

  public boolean refresh(Project project, long number) {
    project = projects.get(project.id());
    if (!project.connected() || project.archived()) return false;
    try {
      store.save(
          project,
          number,
          github.pullRequest(project.repository(), number, github.token(project.installationId())));
      return repositories.syncTicket(project, "", store.get(project.id(), number));
    } catch (GitHubAccessException e) {
      store.failed(project.id(), number, e.getMessage());
    } catch (RuntimeException e) {
      store.failed(project.id(), number, "Could not refresh pull request details. Retry later.");
    }
    return false;
  }

  @Scheduled(
      fixedDelayString = "${daiku.github.poll-ms}",
      initialDelayString = "${daiku.github.poll-ms}")
  public void poll() {
    if (!github.configured()) return;
    for (var project : projects.all()) {
      if (!project.connected() || project.archived()) continue;
      for (long number : links.pullRequestsForProject(project.id())) refresh(project, number);
    }
  }
}
