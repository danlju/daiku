package dev.daiku;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.daiku.board.TicketService;
import dev.daiku.cicd.builds.BuildStore;
import dev.daiku.cicd.github.WebhookService;
import dev.daiku.core.ApplicationException;
import dev.daiku.core.port.ProjectRepository;
import dev.daiku.domain.project.Project;
import dev.daiku.domain.ticket.TicketStatus;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:workspace;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
      "daiku.github.webhook-secret=test-secret",
      "daiku.github.poll-ms=3600000",
      "daiku.github.app-id=",
      "daiku.github.private-key-path="
    })
@AutoConfigureMockMvc
class WorkspaceIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ProjectRepository projects;
  @Autowired TicketService tickets;
  @Autowired BuildStore builds;
  @Autowired WebhookService webhooks;
  @Autowired ObjectMapper json;

  @BeforeEach
  void reset() {
    db.update("DELETE FROM webhook_deliveries");
    db.update("DELETE FROM ticket_events");
    db.update("DELETE FROM tickets");
    db.update("DELETE FROM build_runs");
    db.update("DELETE FROM projects");
  }

  @Test
  void emptyWorkspaceExplainsSetupAndDoesNotInventProjects() throws Exception {
    mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString("Your projects, moving forward.")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("GitHub isn’t configured yet")));
  }

  @Test
  void createProjectAndTicketThenRenderAndMoveOnBoard() throws Exception {
    var session = session();
    String csrf = (String) session.getAttribute("csrf");
    mvc.perform(
            post("/projects/local")
                .session(session)
                .param("_csrf", csrf)
                .param("name", "Daiku test"))
        .andExpect(status().is3xxRedirection());
    Project project = projects.all().getFirst();
    mvc.perform(
            post("/projects/" + project.id() + "/tickets")
                .session(session)
                .param("_csrf", csrf)
                .param("title", "Add meaningful checks")
                .param("acceptanceCriteria", "Tests pass")
                .param("priority", "HIGH"))
        .andExpect(status().is3xxRedirection());
    var ticket = tickets.forProject(project.id()).getFirst();
    mvc.perform(get("/tickets/" + ticket.id()).session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Add meaningful checks")));
    mvc.perform(
            post("/tickets/" + ticket.id())
                .session(session)
                .param("_csrf", csrf)
                .param("version", "0")
                .param("title", ticket.title())
                .param("acceptanceCriteria", "Tests pass")
                .param("priority", "HIGH")
                .param("status", "READY"))
        .andExpect(status().is3xxRedirection());
    assertThat(tickets.get(ticket.id()).status()).isEqualTo(TicketStatus.READY);
    assertThat(tickets.history(ticket.id())).contains("Moved from Suggested to Ready by you");
    mvc.perform(get("/projects/" + project.id()))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Add meaningful checks")));
  }

  @Test
  void boardMovesPreserveTicketDetailsAndRecordHistory() throws Exception {
    var p = projects.createLocal("Board moves");
    long id = tickets.create(p.id(), "Keep details", "Description", "Criteria", "HIGH");
    var session = session();
    String csrf = (String) session.getAttribute("csrf");
    mvc.perform(get("/projects/" + p.id()).session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("data-version=\"0\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/board.js")));
    long version = 0;
    for (var target : TicketStatus.values()) {
      if (target == TicketStatus.SUGGESTED) continue;
      mvc.perform(
              post("/tickets/" + id + "/status")
                  .session(session)
                  .param("_csrf", csrf)
                  .param("version", Long.toString(version))
                  .param("status", target.name()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value(target.name()))
          .andExpect(jsonPath("$.version").value(++version));
    }
    var moved = tickets.get(id);
    assertThat(moved.status()).isEqualTo(TicketStatus.DONE);
    assertThat(moved.title()).isEqualTo("Keep details");
    assertThat(moved.description()).isEqualTo("Description");
    assertThat(moved.acceptanceCriteria()).isEqualTo("Criteria");
    assertThat(moved.priority()).isEqualTo("HIGH");
    assertThat(tickets.history(id)).hasSize(5).contains("Moved from In review to Done by you");
  }

  @Test
  void boardMovesRejectStaleVersionsAndInvalidRequestsWithoutWritingHistory() throws Exception {
    var p = projects.createLocal("Board moves");
    long id = tickets.create(p.id(), "Original", "", "", "MEDIUM");
    tickets.update(id, 0, "Newer title", "New description", "", "HIGH", TicketStatus.READY);
    var session = session();
    String csrf = (String) session.getAttribute("csrf");
    mvc.perform(
            post("/tickets/" + id + "/status")
                .session(session)
                .param("_csrf", csrf)
                .param("version", "0")
                .param("status", "DONE"))
        .andExpect(status().isConflict());
    for (String invalid : new String[] {"UNKNOWN", ""}) {
      mvc.perform(
              post("/tickets/" + id + "/status")
                  .session(session)
                  .param("_csrf", csrf)
                  .param("version", "1")
                  .param("status", invalid))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post("/tickets/999999/status")
                .session(session)
                .param("_csrf", csrf)
                .param("version", "0")
                .param("status", "DONE"))
        .andExpect(status().isNotFound());
    assertThat(tickets.get(id).title()).isEqualTo("Newer title");
    assertThat(tickets.get(id).status()).isEqualTo(TicketStatus.READY);
    assertThat(tickets.get(id).version()).isEqualTo(1);
    assertThat(tickets.history(id)).hasSize(2);
  }

  @Test
  void boardMovesRequireSessionBoundCsrf() throws Exception {
    var p = projects.createLocal("Board moves");
    long id = tickets.create(p.id(), "Protected", "", "", "MEDIUM");
    var first = session();
    var second = session();
    mvc.perform(post("/tickets/" + id + "/status").param("version", "0").param("status", "DONE"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/tickets/" + id + "/status")
                .session(second)
                .param("_csrf", (String) first.getAttribute("csrf"))
                .param("version", "0")
                .param("status", "DONE"))
        .andExpect(status().isForbidden());
    assertThat(tickets.get(id).status()).isEqualTo(TicketStatus.SUGGESTED);
    assertThat(tickets.history(id)).hasSize(1);
  }

  @Test
  void ticketDeletionRequiresConfirmationAndReturnsToItsBoard() throws Exception {
    var p = projects.createLocal("Delete tickets");
    long id = tickets.create(p.id(), "Remove this ticket", "", "", "MEDIUM");
    long keep = tickets.create(p.id(), "Keep this ticket", "", "", "HIGH");
    var session = session();
    mvc.perform(get("/tickets/" + id).session(session))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "href=\"/tickets/" + id + "/delete\">Delete ticket</a>")));
    mvc.perform(get("/tickets/" + id + "/delete").session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Remove this ticket")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("This cannot be undone.")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "href=\"/tickets/" + id + "\">Cancel</a>")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "action=\"/tickets/" + id + "/delete\" method=\"post\"")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("name=\"version\" value=\"0\"")));
    assertThat(tickets.get(id).title()).isEqualTo("Remove this ticket");
    assertThat(tickets.history(id)).hasSize(1);
    mvc.perform(
            post("/tickets/" + id + "/delete")
                .session(session)
                .param("_csrf", (String) session.getAttribute("csrf"))
                .param("version", "0"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/projects/" + p.id()))
        .andExpect(flash().attribute("notice", "Ticket deleted."));
    mvc.perform(get("/tickets/" + id)).andExpect(status().isNotFound());
    assertThat(tickets.history(id)).isEmpty();
    assertThat(tickets.forProject(p.id())).extracting(t -> t.id()).containsExactly(keep);
    assertThat(tickets.history(keep)).hasSize(1);
  }

  @Test
  void ticketDeletionRejectsStaleVersionsAndMissingTickets() throws Exception {
    var p = projects.createLocal("Delete tickets");
    long id = tickets.create(p.id(), "Original", "", "", "MEDIUM");
    tickets.update(id, 0, "New title", "", "", "HIGH", TicketStatus.READY);
    var session = session();
    String csrf = (String) session.getAttribute("csrf");
    mvc.perform(
            post("/tickets/" + id + "/delete")
                .session(session)
                .param("_csrf", csrf)
                .param("version", "0"))
        .andExpect(status().isConflict())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Reload before deleting")));
    mvc.perform(post("/tickets/" + id + "/delete").session(session).param("_csrf", csrf))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/tickets/999999/delete")).andExpect(status().isNotFound());
    mvc.perform(
            post("/tickets/999999/delete")
                .session(session)
                .param("_csrf", csrf)
                .param("version", "0"))
        .andExpect(status().isNotFound());
    assertThat(tickets.get(id).title()).isEqualTo("New title");
    assertThat(tickets.history(id)).hasSize(2);
  }

  @Test
  void ticketDeletionRequiresSessionBoundCsrf() throws Exception {
    var p = projects.createLocal("Delete tickets");
    long id = tickets.create(p.id(), "Protected", "", "", "MEDIUM");
    var first = session();
    var second = session();
    mvc.perform(post("/tickets/" + id + "/delete").session(first).param("version", "0"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/tickets/" + id + "/delete")
                .session(second)
                .param("_csrf", (String) first.getAttribute("csrf"))
                .param("version", "0"))
        .andExpect(status().isForbidden());
    assertThat(tickets.get(id).title()).isEqualTo("Protected");
    assertThat(tickets.history(id)).hasSize(1);
  }

  @Test
  void rejectsMissingCsrfAndCrossSessionCsrf() throws Exception {
    mvc.perform(post("/projects/local").param("name", "Unwanted project"))
        .andExpect(status().isForbidden());
    var first = session();
    var second = session();
    mvc.perform(
            post("/projects/local")
                .session(second)
                .param("_csrf", (String) first.getAttribute("csrf"))
                .param("name", "Unwanted"))
        .andExpect(status().isForbidden());
    assertThat(projects.all()).isEmpty();
  }

  @Test
  void ticketContentIsEscapedAndUnknownProjectsAreNotFound() throws Exception {
    var p = projects.createLocal("<script>alert(1)</script>");
    tickets.create(p.id(), "<img src=x onerror=alert(1)>", "<script>bad()</script>", "", "MEDIUM");
    mvc.perform(get("/projects/" + p.id()))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;img")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<script>bad()"))));
    mvc.perform(get("/projects/999999")).andExpect(status().isNotFound());
  }

  @Test
  void rejectsBlankTicketsAndInvalidPrioritiesWithoutPartialWrites() {
    var p = projects.createLocal("Test");
    assertThatThrownBy(() -> tickets.create(p.id(), " ", "", "", "MEDIUM"))
        .isInstanceOf(ApplicationException.class);
    assertThatThrownBy(() -> tickets.create(p.id(), "Ticket", "", "", "URGENT"))
        .isInstanceOf(ApplicationException.class);
    assertThat(tickets.forProject(p.id())).isEmpty();
  }

  @Test
  void conflictingEditsCannotOverwriteNewerChangesOrAppendHistory() {
    var p = projects.createLocal("Test");
    long id = tickets.create(p.id(), "Original", "", "", "MEDIUM");
    tickets.update(id, 0, "Newer", "", "", "HIGH", TicketStatus.READY);
    assertThatThrownBy(() -> tickets.update(id, 0, "Stale", "", "", "LOW", TicketStatus.DONE))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            e -> assertThat(e.kind()).isEqualTo(ApplicationException.Kind.CONFLICT));
    assertThat(tickets.get(id).title()).isEqualTo("Newer");
    assertThat(tickets.history(id)).hasSize(2);
  }

  @Test
  void applicationErrorsAreMappedToBadRequestAndConflictResponses() throws Exception {
    var p = projects.createLocal("Test");
    var session = session();
    String csrf = (String) session.getAttribute("csrf");
    mvc.perform(
            post("/projects/" + p.id() + "/tickets")
                .session(session)
                .param("_csrf", csrf)
                .param("title", " "))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Title is required")));
    assertThat(tickets.forProject(p.id())).isEmpty();
    long id = tickets.create(p.id(), "Original", "", "", "MEDIUM");
    tickets.update(id, 0, "Newer", "", "", "HIGH", TicketStatus.READY);
    mvc.perform(
            post("/tickets/" + id)
                .session(session)
                .param("_csrf", csrf)
                .param("version", "0")
                .param("title", "Stale")
                .param("priority", "LOW")
                .param("status", "DONE"))
        .andExpect(status().isConflict())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Reload before saving")));
    assertThat(tickets.get(id).title()).isEqualTo("Newer");
    assertThat(tickets.history(id)).hasSize(2);
  }

  @Test
  void duplicateSignedWebhooksAreRecordedOnceAndProcessed() throws Exception {
    Project p = connected();
    byte[] payload = payload("completed", "success", "2026-11-04T10:00:00Z", 1);
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/webhooks/github")
                  .contentType("application/json")
                  .content(payload)
                  .header("X-GitHub-Delivery", "delivery-1")
                  .header("X-GitHub-Event", "workflow_run")
                  .header("X-Hub-Signature-256", sign(payload)))
          .andExpect(status().isAccepted());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM webhook_deliveries", Integer.class))
        .isEqualTo(1);
    webhooks.processPending();
    assertThat(builds.recent(p.id())).hasSize(1);
    assertThat(builds.recent(p.id()).getFirst().getLabel()).isEqualTo("success");
    assertThat(db.queryForObject("SELECT state FROM webhook_deliveries", String.class))
        .isEqualTo("PROCESSED");
    mvc.perform(get("/projects/" + p.id()))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("success")));
  }

  @Test
  void unsignedAndTamperedWebhooksNeverEnterTheInbox() throws Exception {
    byte[] payload = payload("completed", "success", "2026-11-04T10:00:00Z", 1);
    mvc.perform(
            post("/webhooks/github")
                .content(payload)
                .header("X-GitHub-Delivery", "bad")
                .header("X-GitHub-Event", "workflow_run"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/webhooks/github")
                .content(payload)
                .header("X-GitHub-Delivery", "bad")
                .header("X-GitHub-Event", "workflow_run")
                .header("X-Hub-Signature-256", sign("different".getBytes(StandardCharsets.UTF_8))))
        .andExpect(status().isUnauthorized());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM webhook_deliveries", Integer.class))
        .isZero();
  }

  @Test
  void delayedRunEventsCannotRegressBuildStateAndRerunsAreTracked() throws Exception {
    var p = connected();
    ingest(p, "completed", "failure", "2026-11-04T10:10:00Z", 1);
    ingest(p, "in_progress", null, "2026-11-04T10:00:00Z", 1);
    assertThat(builds.recent(p.id()).getFirst().getLabel()).isEqualTo("failure");
    ingest(p, "in_progress", null, "2026-11-04T10:20:00Z", 2);
    ingest(p, "completed", "failure", "2026-11-04T10:30:00Z", 1);
    assertThat(builds.recent(p.id()).getFirst().getLabel()).isEqualTo("in_progress");
    ingest(p, "completed", "success", "2026-11-04T10:40:00Z", 2);
    assertThat(builds.recent(p.id())).hasSize(1);
    assertThat(builds.recent(p.id()).getFirst().getLabel()).isEqualTo("success");
  }

  @Test
  void webhookForAnotherInstallationCannotUpdateConnectedProject() throws Exception {
    var p = connected();
    String changed =
        new String(
                payload("completed", "success", "2026-11-04T10:00:00Z", 1), StandardCharsets.UTF_8)
            .replace("\"installation\":{\"id\":42}", "\"installation\":{\"id\":43}");
    byte[] bytes = changed.getBytes(StandardCharsets.UTF_8);
    webhooks.receive("other-installation", "workflow_run", sign(bytes), bytes);
    webhooks.processPending();
    assertThat(builds.recent(p.id())).isEmpty();
  }

  @Test
  void completionWinsWhenGithubTimestampsHaveTheSameSecond() throws Exception {
    var p = connected();
    ingest(p, "in_progress", null, "2026-11-04T10:00:00Z", 1);
    ingest(p, "completed", "success", "2026-11-04T10:00:00Z", 1);
    ingest(p, "queued", null, "2026-11-04T10:00:00Z", 1);
    assertThat(builds.recent(p.id()).getFirst().getLabel()).isEqualTo("success");
  }

  private MockHttpSession session() throws Exception {
    return (MockHttpSession)
        mvc.perform(get("/projects/new")).andReturn().getRequest().getSession();
  }

  private Project connected() {
    return projects.connect(100, 42, "example", "owner/example", "main");
  }

  private void ingest(Project p, String status, String conclusion, String time, int attempt)
      throws Exception {
    builds.ingest(
        p, json.readTree(payload(status, conclusion, time, attempt)).path("workflow_run"));
  }

  private byte[] payload(String status, String conclusion, String time, int attempt) {
    return ("{\"installation\":{\"id\":42},\"repository\":{\"id\":100},\"workflow_run\":{\"id\":123,\"name\":\"CI\",\"head_branch\":\"main\",\"head_sha\":\"abcdef123456\",\"status\":\""
            + status
            + "\",\"conclusion\":"
            + (conclusion == null ? "null" : "\"" + conclusion + "\"")
            + ",\"html_url\":\"https://github.com/owner/example/actions/runs/123\",\"run_number\":1,\"run_attempt\":"
            + attempt
            + ",\"updated_at\":\""
            + time
            + "\"}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private String sign(byte[] bytes) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(bytes));
  }
}
