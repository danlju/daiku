package dev.daiku.cicd.github;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.daiku.DaikuApplication;
import dev.daiku.board.TicketService;
import dev.daiku.cicd.builds.BuildStore;
import dev.daiku.core.port.ProjectRepository;
import dev.daiku.domain.project.Project;
import dev.daiku.domain.ticket.TicketStatus;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = {DaikuApplication.class, ProjectConnectionIntegrationTest.GitHubConfiguration.class},
    properties = {
      "spring.datasource.url=jdbc:h2:mem:project_connections;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
      "daiku.github.app-id=",
      "daiku.github.private-key-path=",
      "daiku.github.webhook-secret=",
      "daiku.github.poll-ms=3600000"
    })
@AutoConfigureMockMvc
class TicketDevelopmentIntegrationTest {
  private static final String HEAD = "a".repeat(40);
  private static final String MERGE = "b".repeat(40);
  private static final String NEXT = "c".repeat(40);
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ProjectRepository projects;
  @Autowired TicketService tickets;
  @Autowired BuildStore builds;
  @Autowired PullRequestStore pullRequestStore;
  @Autowired PullRequestService pullRequests;
  @Autowired MockRestServiceServer githubServer;
  @Autowired ObjectMapper json;

  @BeforeEach
  void reset() {
    githubServer.reset();
    db.update("DELETE FROM webhook_deliveries");
    db.update("DELETE FROM ticket_events");
    db.update("DELETE FROM tickets");
    db.update("DELETE FROM build_runs");
    db.update("DELETE FROM pull_requests");
    db.update("DELETE FROM projects");
  }

  @AfterEach
  void verifyRequests() {
    githubServer.verify();
  }

  @Test
  void savesLinksShowsPrAndBuildStateThenRetainsLinksAcrossEditsAndMoves() throws Exception {
    var p = connected();
    long id = tickets.create(p.id(), "Ticket", "Description", "Criteria", "HIGH");
    var session = session();
    expectPr(pr("open", true, false, HEAD));
    expectBuilds(
        List.of(
            run(101, 100, HEAD, "feature/work", "Current PR build"),
            run(102, 100, NEXT, "feature/work", "Unrelated green build")));
    mvc.perform(
            post("/tickets/" + id + "/development")
                .session(session)
                .param("_csrf", csrf(session))
                .param("version", "0")
                .param("branch", "feature/work")
                .param("pullRequest", "https://github.com/owner/example/pull/7/"))
        .andExpect(redirectedUrl("/tickets/" + id));
    assertThat(tickets.get(id).branch()).isEqualTo("feature/work");
    assertThat(tickets.get(id).pullRequestNumber()).isEqualTo(7L);
    assertThat(tickets.history(id))
        .hasSize(2)
        .anyMatch(e -> e.contains("Development links updated"));
    mvc.perform(get("/tickets/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Draft")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString("&lt;script&gt;PR title&lt;/script&gt;")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Current PR build")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Unrelated green build"))));
    tickets.update(
        id, 1, "Edited", "Updated description", "Criteria", "MEDIUM", TicketStatus.READY);
    tickets.move(id, 2, TicketStatus.IN_PROGRESS);
    assertThat(tickets.get(id).branch()).isEqualTo("feature/work");
    assertThat(tickets.get(id).pullRequestNumber()).isEqualTo(7L);
    mvc.perform(
            post("/tickets/" + id + "/development")
                .session(session)
                .param("_csrf", csrf(session))
                .param("version", "3")
                .param("branch", "")
                .param("pullRequest", ""))
        .andExpect(redirectedUrl("/tickets/" + id))
        .andExpect(flash().attribute("notice", "Development links cleared."));
    assertThat(tickets.get(id).branch()).isEmpty();
    assertThat(tickets.get(id).pullRequestNumber()).isNull();
    assertThat(tickets.get(id).description()).isEqualTo("Updated description");
  }

  @Test
  void branchOnlyLinkRefreshesFilteredBuildsWithoutRequiringPrAccess() throws Exception {
    var p = connected();
    long id = tickets.create(p.id(), "Branch only", "", "", "MEDIUM");
    String branch = "feature/fix+foo&bar";
    token();
    var body = json.createObjectNode();
    body.putArray("workflow_runs").add(run(101, 100, HEAD, branch, "Branch workflow"));
    githubServer
        .expect(
            requestTo(
                "https://api.github.com/repos/owner/example/actions/runs?per_page=30&branch=feature%2Ffix%2Bfoo%26bar"))
        .andRespond(withSuccess(body.toString(), MediaType.APPLICATION_JSON));
    var session = session();
    mvc.perform(
            post("/tickets/" + id + "/development")
                .session(session)
                .param("_csrf", csrf(session))
                .param("version", "0")
                .param("branch", branch))
        .andExpect(redirectedUrl("/tickets/" + id));
    mvc.perform(get("/tickets/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Branch workflow")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("feature/fix+foo&amp;bar")));
    assertThat(tickets.get(id).pullRequestNumber()).isNull();
  }

  @Test
  void rejectsInvalidLinksStaleWritesAndArchivedChangesWithoutPartialHistory() throws Exception {
    var p = connected();
    long id = tickets.create(p.id(), "Original", "", "", "MEDIUM");
    var session = session();
    for (String branch :
        List.of(
            "bad branch",
            "../evil",
            "feature//bad",
            "x.lock",
            "-option",
            "bad?ref",
            "a@{b",
            ".hidden/x")) {
      mvc.perform(
              post("/tickets/" + id + "/development")
                  .session(session)
                  .param("_csrf", csrf(session))
                  .param("version", "0")
                  .param("branch", branch))
          .andExpect(status().isBadRequest());
    }
    for (String input :
        List.of(
            "0",
            "-1",
            "#0",
            "999999999999999999999",
            "https://github.com/other/repo/pull/7",
            "javascript:alert(1)",
            "https://github.com/owner/example/pull/7?x=1")) {
      mvc.perform(
              post("/tickets/" + id + "/development")
                  .session(session)
                  .param("_csrf", csrf(session))
                  .param("version", "0")
                  .param("pullRequest", input))
          .andExpect(status().isBadRequest());
    }
    assertThat(tickets.history(id)).hasSize(1);
    assertThat(tickets.get(id).version()).isZero();
    tickets.linkDevelopment(id, 0, "feature/work", "#7");
    mvc.perform(
            post("/tickets/" + id + "/development")
                .session(session)
                .param("_csrf", csrf(session))
                .param("version", "0")
                .param("pullRequest", "8"))
        .andExpect(status().isConflict());
    assertThat(tickets.get(id).pullRequestNumber()).isEqualTo(7L);
    projects.setArchived(p.id(), true);
    for (String action : List.of("/development", "/development/refresh")) {
      mvc.perform(
              post("/tickets/" + id + action)
                  .session(session)
                  .param("_csrf", csrf(session))
                  .param("version", "1")
                  .param("branch", "changed"))
          .andExpect(status().isConflict());
    }
    mvc.perform(get("/tickets/" + id))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Save development links"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Refresh development status"))));
    assertThat(tickets.history(id)).hasSize(2);
  }

  @Test
  void csrfLocalProjectsAndMissingTicketsAreProtected() throws Exception {
    var p = projects.createLocal("Local");
    long id = tickets.create(p.id(), "Original", "", "", "MEDIUM");
    var first = session();
    var second = session();
    for (String action : List.of("/development", "/development/refresh")) {
      mvc.perform(post("/tickets/" + id + action).session(first).param("version", "0"))
          .andExpect(status().isForbidden());
      mvc.perform(
              post("/tickets/" + id + action)
                  .session(second)
                  .param("_csrf", csrf(first))
                  .param("version", "0"))
          .andExpect(status().isForbidden());
      mvc.perform(
              post("/tickets/" + id + action)
                  .session(first)
                  .param("_csrf", csrf(first))
                  .param("version", "0"))
          .andExpect(status().isConflict());
      mvc.perform(
              post("/tickets/999999" + action)
                  .session(first)
                  .param("_csrf", csrf(first))
                  .param("version", "0"))
          .andExpect(status().isNotFound());
    }
    mvc.perform(get("/tickets/" + id))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString("Connect this project to GitHub")));
    assertThat(tickets.history(id)).hasSize(1);
  }

  @Test
  void prBuildMatchingExcludesOldHeadsAndOtherProjectsAndIncludesMergeCommit() {
    var p = connected();
    long id = tickets.create(p.id(), "PR", "", "", "MEDIUM");
    tickets.linkDevelopment(id, 0, "feature/work", "7");
    pullRequestStore.save(p, 7, pr("open", false, false, HEAD));
    builds.ingest(p, run(1, 100, HEAD, "feature/work", "Head build"));
    builds.ingest(p, run(2, 100, MERGE, "feature/work", "Merge build"));
    builds.ingest(p, run(3, 100, NEXT, "feature/work", "Other commit"));
    var other = projects.connect(200, 42, "Other", "owner/other", "main");
    var otherRun = run(4, 200, HEAD, "feature/work", "Other project");
    otherRun.put("html_url", "https://github.com/owner/other/actions/runs/4");
    builds.ingest(other, otherRun);
    assertThat(builds.related(p, tickets.get(id), pullRequestStore.get(p.id(), 7)))
        .extracting(b -> b.id())
        .containsExactly(2L, 1L);
    var changed = pr("open", false, false, "d".repeat(40));
    changed.put("merge_commit_sha", "e".repeat(40));
    changed.put("updated_at", "2026-11-08T02:00:00Z");
    pullRequestStore.save(p, 7, changed);
    assertThat(builds.related(p, tickets.get(id), pullRequestStore.get(p.id(), 7))).isEmpty();
    pullRequestStore.save(p, 7, pr("open", false, false, HEAD));
    assertThat(pullRequestStore.get(p.id(), 7).headSha()).isEqualTo("d".repeat(40));
  }

  @Test
  void branchBuildsExcludeForksAndEnrichLegacyRunsOnRefresh() {
    var p = connected();
    long id = tickets.create(p.id(), "Branch", "", "", "MEDIUM");
    tickets.linkDevelopment(id, 0, "feature/work", "");
    builds.ingest(p, run(1, 100, HEAD, "feature/work", "Matching"));
    builds.ingest(p, run(2, 999, NEXT, "feature/work", "Fork with same branch"));
    builds.ingest(p, run(3, 100, MERGE, "other-branch", "Other branch"));
    var legacy = run(4, 100, NEXT, "feature/work", "Legacy");
    legacy.remove("head_repository");
    builds.ingest(p, legacy);
    assertThat(builds.related(p, tickets.get(id), null))
        .extracting(b -> b.id())
        .containsExactly(1L);
    builds.ingest(p, run(4, 100, NEXT, "feature/work", "Legacy"));
    assertThat(builds.related(p, tickets.get(id), null))
        .extracting(b -> b.id())
        .containsExactly(4L, 1L);
  }

  @Test
  void missingPrPermissionRetainsSavedLinksAndLastKnownStatusWithSafeError() throws Exception {
    var p = connected();
    long id = tickets.create(p.id(), "PR", "", "", "MEDIUM");
    pullRequestStore.save(p, 7, pr("open", false, false, HEAD));
    token();
    githubServer
        .expect(requestTo("https://api.github.com/repos/owner/example/pulls/7"))
        .andRespond(withForbiddenRequest().body("sensitive upstream body"));
    var session = session();
    mvc.perform(
            post("/tickets/" + id + "/development")
                .session(session)
                .param("_csrf", csrf(session))
                .param("version", "0")
                .param("pullRequest", "7"))
        .andExpect(redirectedUrl("/tickets/" + id))
        .andExpect(
            flash()
                .attribute(
                    "notice", org.hamcrest.Matchers.containsString("Development links saved")));
    assertThat(tickets.get(id).pullRequestNumber()).isEqualTo(7L);
    assertThat(pullRequestStore.get(p.id(), 7).getLabel()).isEqualTo("Open");
    mvc.perform(get("/tickets/" + id))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Pull requests read permission")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("last known state")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive upstream body"))));
  }

  @Test
  void pollingDeduplicatesLinkedPrsSkipsArchivedProjectsAndStopsAfterUnlink() {
    var p = connected();
    long first = tickets.create(p.id(), "First", "", "", "MEDIUM");
    long second = tickets.create(p.id(), "Second", "", "", "MEDIUM");
    tickets.linkDevelopment(first, 0, "", "7");
    tickets.linkDevelopment(second, 0, "", "7");
    var archived = projects.connect(200, 42, "Archived", "owner/archived", "main");
    long ignored = tickets.create(archived.id(), "Ignored", "", "", "MEDIUM");
    tickets.linkDevelopment(ignored, 0, "", "8");
    projects.setArchived(archived.id(), true);
    expectPr(pr("closed", false, true, HEAD));
    expectBuilds(List.of());
    pullRequests.poll();
    assertThat(pullRequestStore.get(p.id(), 7).getLabel()).isEqualTo("Merged");
    assertThat(pullRequestStore.get(archived.id(), 8)).isNull();
    githubServer.verify();
    githubServer.reset();
    tickets.linkDevelopment(first, 1, "", "");
    tickets.delete(second, 1);
    pullRequests.poll();
    projects.setArchived(p.id(), true);
    pullRequestStore.save(p, 7, pr("open", false, false, NEXT));
    assertThat(pullRequestStore.get(p.id(), 7).getLabel()).isEqualTo("Merged");
  }

  @Test
  void displaysAllPrStatesAndManualRefreshUsesTheSavedLink() throws Exception {
    var p = connected();
    long id = tickets.create(p.id(), "PR", "", "", "MEDIUM");
    tickets.linkDevelopment(id, 0, "", "7");
    for (String state : List.of("open", "closed")) {
      pullRequestStore.save(p, 7, pr(state, false, false, HEAD));
      mvc.perform(get("/tickets/" + id))
          .andExpect(status().isOk())
          .andExpect(
              content()
                  .string(
                      org.hamcrest.Matchers.containsString(
                          state.equals("open") ? "Open</strong>" : "Closed</strong>")));
    }
    expectPr(pr("closed", false, true, HEAD));
    expectBuilds(List.of());
    var session = session();
    mvc.perform(
            post("/tickets/" + id + "/development/refresh")
                .session(session)
                .param("_csrf", csrf(session)))
        .andExpect(redirectedUrl("/tickets/" + id));
    mvc.perform(get("/tickets/" + id))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Merged</strong>")));
  }

  private Project connected() {
    return projects.connect(100, 42, "Example", "owner/example", "main");
  }

  private MockHttpSession session() throws Exception {
    return (MockHttpSession)
        mvc.perform(get("/projects/new")).andReturn().getRequest().getSession();
  }

  private String csrf(MockHttpSession session) {
    return (String) session.getAttribute("csrf");
  }

  private ObjectNode pr(String state, boolean draft, boolean merged, String sha) {
    var node =
        json.createObjectNode()
            .put("number", 7)
            .put("title", "<script>PR title</script>")
            .put("state", state)
            .put("draft", draft)
            .put("merged", merged)
            .put("merge_commit_sha", MERGE)
            .put("updated_at", "2026-11-08T01:00:00Z");
    node.putObject("base").putObject("repo").put("id", 100);
    node.putObject("head").put("ref", "feature/work").put("sha", sha);
    return node;
  }

  private ObjectNode run(long id, long headRepository, String sha, String branch, String name) {
    var node =
        json.createObjectNode()
            .put("id", id)
            .put("name", name)
            .put("head_branch", branch)
            .put("head_sha", sha)
            .put("status", "completed")
            .put("conclusion", "success")
            .put("html_url", "https://github.com/owner/example/actions/runs/" + id)
            .put("run_number", id)
            .put("run_attempt", 1)
            .put("updated_at", "2026-11-08T01:00:00Z");
    node.putObject("head_repository").put("id", headRepository);
    return node;
  }

  private void token() {
    githubServer
        .expect(requestTo("https://api.github.com/app/installations/42/access_tokens"))
        .andRespond(withSuccess("{\"token\":\"test-token\"}", MediaType.APPLICATION_JSON));
  }

  private void expectPr(ObjectNode pr) {
    token();
    githubServer
        .expect(requestTo("https://api.github.com/repos/owner/example/pulls/7"))
        .andRespond(withSuccess(pr.toString(), MediaType.APPLICATION_JSON));
  }

  private void expectBuilds(List<ObjectNode> runs) {
    token();
    var node = json.createObjectNode();
    var list = node.putArray("workflow_runs");
    runs.forEach(list::add);
    githubServer
        .expect(
            requestTo(
                "https://api.github.com/repos/owner/example/actions/runs?per_page=30&head_sha="
                    + HEAD))
        .andRespond(withSuccess(node.toString(), MediaType.APPLICATION_JSON));
    githubServer
        .expect(
            requestTo(
                "https://api.github.com/repos/owner/example/actions/runs?per_page=30&head_sha="
                    + MERGE))
        .andRespond(withSuccess("{\"workflow_runs\":[]}", MediaType.APPLICATION_JSON));
  }
}
