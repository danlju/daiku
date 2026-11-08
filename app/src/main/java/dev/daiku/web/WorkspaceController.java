package dev.daiku.web;

import dev.daiku.board.TicketService;
import dev.daiku.cicd.builds.BuildStore;
import dev.daiku.cicd.github.GitHubAccessException;
import dev.daiku.cicd.github.GitHubClient;
import dev.daiku.cicd.github.RepositoryService;
import dev.daiku.core.ApplicationException;
import dev.daiku.core.port.ProjectRepository;
import dev.daiku.domain.ticket.Ticket;
import dev.daiku.domain.ticket.TicketStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WorkspaceController {
  private final ProjectRepository projects;
  private final TicketService tickets;
  private final BuildStore builds;
  private final GitHubClient github;
  private final RepositoryService repositories;

  public WorkspaceController(
      ProjectRepository projects,
      TicketService tickets,
      BuildStore builds,
      GitHubClient github,
      RepositoryService repositories) {
    this.projects = projects;
    this.tickets = tickets;
    this.builds = builds;
    this.github = github;
    this.repositories = repositories;
  }

  @ModelAttribute
  public void common(Model model) {
    model.addAttribute("projects", projects.all().stream().filter(p -> !p.archived()).toList());
    model.addAttribute("githubConfigured", github.configured());
    model.addAttribute("statuses", TicketStatus.values());
  }

  @GetMapping("/")
  public String home(Model model) {
    var all = projects.all().stream().filter(p -> !p.archived()).toList();
    if (!all.isEmpty()) return "redirect:/projects/" + all.getFirst().id();
    model.addAttribute("pageTitle", "Your workspace");
    return "connect";
  }

  @GetMapping("/projects/archived")
  public String archived(Model model) {
    model.addAttribute("pageTitle", "Archived projects");
    model.addAttribute(
        "archivedProjects", projects.all().stream().filter(p -> p.archived()).toList());
    return "archived-projects";
  }

  @PostMapping("/projects/{id}/archive")
  public String archive(@PathVariable long id, RedirectAttributes flash) {
    projects.setArchived(id, true);
    flash.addFlashAttribute(
        "notice", "Project archived. You can restore it from Archived projects.");
    return "redirect:/projects/archived";
  }

  @PostMapping("/projects/{id}/restore")
  public String restore(@PathVariable long id, RedirectAttributes flash) {
    projects.setArchived(id, false);
    flash.addFlashAttribute("notice", "Project restored.");
    return "redirect:/projects/" + id;
  }

  @GetMapping("/projects/new")
  public String connect(Model model) {
    model.addAttribute("pageTitle", "Add a project");
    return "connect";
  }

  @PostMapping("/projects/local")
  public String createLocal(@RequestParam String name) {
    return "redirect:/projects/"
        + projects.createLocal(TicketService.required(name, 100, "Project name")).id();
  }

  @PostMapping("/projects/connect")
  public String connectRepo(@RequestParam String repository, RedirectAttributes flash) {
    try {
      var project = repositories.connect(repository);
      if (project.archived()) {
        flash.addFlashAttribute(
            "notice", "This repository belongs to an archived project. Restore it to resume work.");
        return "redirect:/projects/" + project.id();
      }
      if (!repositories.sync(project))
        flash.addFlashAttribute(
            "notice", "Repository connected. Build refresh failed; you can retry from the board.");
      return "redirect:/projects/" + project.id();
    } catch (IllegalArgumentException e) {
      flash.addFlashAttribute("notice", "Enter a valid repository such as owner/repository.");
    } catch (GitHubAccessException e) {
      flash.addFlashAttribute("notice", e.getMessage());
    } catch (IllegalStateException e) {
      flash.addFlashAttribute(
          "notice",
          "Could not connect. Check the App ID, RSA private key, repository permissions, and GitHub"
              + " App installation.");
    }
    return "redirect:/projects/new";
  }

  @GetMapping("/projects/{id}/connect")
  public String connectProject(@PathVariable long id, Model model) {
    var project = projects.get(id);
    if (project.archived() || project.connected()) return "redirect:/projects/" + id;
    model.addAttribute("project", project);
    model.addAttribute("pageTitle", "Connect GitHub · " + project.name());
    return "connect-project";
  }

  @PostMapping("/projects/{id}/connect")
  public String connectProjectRepo(
      @PathVariable long id, @RequestParam String repository, RedirectAttributes flash) {
    try {
      var project = repositories.connect(id, repository);
      flash.addFlashAttribute(
          "notice",
          repositories.sync(project)
              ? "GitHub repository connected. Your project and tickets are preserved."
              : "Repository connected. Build refresh failed; you can retry from the board.");
      return "redirect:/projects/" + id;
    } catch (ApplicationException e) {
      if (e.kind() != ApplicationException.Kind.CONFLICT) throw e;
      flash.addFlashAttribute("notice", e.getMessage());
      return "redirect:/projects/" + id;
    } catch (IllegalArgumentException e) {
      flash.addFlashAttribute("notice", "Enter a valid repository such as owner/repository.");
    } catch (GitHubAccessException e) {
      flash.addFlashAttribute("notice", e.getMessage());
    } catch (IllegalStateException e) {
      flash.addFlashAttribute("notice", "Could not connect the repository. Try again later.");
    }
    return "redirect:/projects/" + id + "/connect";
  }

  @GetMapping("/projects/{id}")
  public String board(@PathVariable long id, Model model) {
    var project = projects.get(id);
    var allTickets = tickets.forProject(id);
    var columns =
        new java.util.LinkedHashMap<TicketStatus, java.util.List<dev.daiku.domain.ticket.Ticket>>();
    for (var status : TicketStatus.values())
      columns.put(status, allTickets.stream().filter(t -> t.status() == status).toList());
    model.addAttribute("project", project);
    model.addAttribute("pageTitle", project.name());
    model.addAttribute("columns", columns);
    model.addAttribute("ticketCount", allTickets.size());
    model.addAttribute("builds", builds.recent(id));
    return "board";
  }

  @PostMapping("/projects/{id}/refresh")
  public String refresh(@PathVariable long id, RedirectAttributes flash) {
    var project = projects.get(id);
    if (project.archived())
      throw new ApplicationException(
          ApplicationException.Kind.CONFLICT, "Restore this project before refreshing builds.");
    boolean ok = repositories.sync(project);
    flash.addFlashAttribute(
        "notice",
        ok
            ? "Build status refreshed from GitHub."
            : "Refresh failed. Check GitHub App access and try again.");
    return "redirect:/projects/" + id;
  }

  @PostMapping("/projects/{id}/tickets")
  public String createTicket(
      @PathVariable long id,
      @RequestParam String title,
      @RequestParam(defaultValue = "") String description,
      @RequestParam(defaultValue = "") String acceptanceCriteria,
      @RequestParam(defaultValue = "MEDIUM") String priority) {
    projects.get(id);
    long ticketId = tickets.create(id, title, description, acceptanceCriteria, priority);
    return "redirect:/tickets/" + ticketId;
  }

  @GetMapping("/tickets/{id}")
  public String ticket(@PathVariable long id, Model model) {
    var ticket = tickets.get(id);
    model.addAttribute("ticket", ticket);
    model.addAttribute("project", projects.get(ticket.projectId()));
    model.addAttribute("pageTitle", ticket.title());
    model.addAttribute("history", tickets.history(id));
    return "ticket";
  }

  @GetMapping("/tickets/{id}/delete")
  public String confirmDeleteTicket(@PathVariable long id, Model model) {
    var ticket = tickets.get(id);
    model.addAttribute("ticket", ticket);
    model.addAttribute("project", projects.get(ticket.projectId()));
    model.addAttribute("pageTitle", "Delete ticket");
    return "delete-ticket";
  }

  @PostMapping("/tickets/{id}/delete")
  public String deleteTicket(
      @PathVariable long id, @RequestParam long version, RedirectAttributes flash) {
    long projectId = tickets.delete(id, version);
    flash.addFlashAttribute("notice", "Ticket deleted.");
    return "redirect:/projects/" + projectId;
  }

  @PostMapping("/tickets/{id}/status")
  @ResponseBody
  public Ticket moveTicket(
      @PathVariable long id, @RequestParam long version, @RequestParam TicketStatus status) {
    return tickets.move(id, version, status);
  }

  @PostMapping("/tickets/{id}")
  public String updateTicket(
      @PathVariable long id,
      @RequestParam long version,
      @RequestParam String title,
      @RequestParam(defaultValue = "") String description,
      @RequestParam(defaultValue = "") String acceptanceCriteria,
      @RequestParam String priority,
      @RequestParam TicketStatus status,
      RedirectAttributes flash) {
    tickets.update(id, version, title, description, acceptanceCriteria, priority, status);
    flash.addFlashAttribute("notice", "Ticket saved.");
    return "redirect:/tickets/" + id;
  }
}
