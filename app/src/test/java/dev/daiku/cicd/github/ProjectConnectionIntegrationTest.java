package dev.daiku.cicd.github;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.daiku.DaikuApplication;
import dev.daiku.board.TicketService;
import dev.daiku.cicd.builds.BuildStore;
import dev.daiku.core.ApplicationException;
import dev.daiku.core.port.ProjectRepository;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

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
class ProjectConnectionIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ProjectRepository projects;
  @Autowired TicketService tickets;
  @Autowired BuildStore builds;
  @Autowired MockRestServiceServer githubServer;

  @TestConfiguration(proxyBeanMethods = false)
  static class GitHubConfiguration {
    private final RestClient.Builder builder = RestClient.builder();

    @Bean
    MockRestServiceServer githubServer() {
      return MockRestServiceServer.bindTo(builder).build();
    }

    @Bean
    @Primary
    GitHubClient connectionTestClient(ObjectMapper json, MockRestServiceServer githubServer)
        throws Exception {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pem = Files.createTempFile("daiku-test-app-", ".pem");
      pem.toFile().deleteOnExit();
      Files.writeString(
          pem,
          "-----BEGIN PRIVATE KEY-----\n"
              + Base64.getEncoder()
                  .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
              + "\n-----END PRIVATE KEY-----\n");
      return new GitHubClient("1234", pem.toString(), "https://api.github.com", json, builder);
    }
  }

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
  void verifyGithubRequests() {
    githubServer.verify();
  }

  @Test
  void connectsLocalProjectInPlaceAndImportsBuildsWithoutChangingTickets() throws Exception {
    var p = projects.createLocal("My local name");
    long ticketId = tickets.create(p.id(), "Existing ticket", "Description", "Criteria", "HIGH");
    var before = tickets.get(ticketId);
    var history = tickets.history(ticketId);
    var session = session();
    mvc.perform(get("/projects/" + p.id()).session(session))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString("/projects/" + p.id() + "/connect")));
    mvc.perform(get("/projects/" + p.id() + "/connect").session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("My local name")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Cancel")));
    assertThat(projects.get(p.id())).isEqualTo(p);
    discovery();
    sync(false);
    mvc.perform(
            post("/projects/" + p.id() + "/connect")
                .session(session)
                .param("_csrf", csrf(session))
                .param("repository", "https://github.com/owner/example.git"))
        .andExpect(redirectedUrl("/projects/" + p.id()))
        .andExpect(
            flash()
                .attribute(
                    "notice",
                    "GitHub repository connected. Your project and tickets are preserved."));
    var connected = projects.get(p.id());
    assertThat(connected.name()).isEqualTo(p.name());
    assertThat(connected.repository()).isEqualTo("owner/example");
    assertThat(connected.repositoryId()).isEqualTo(100L);
    assertThat(connected.installationId()).isEqualTo(42L);
    assertThat(connected.defaultBranch()).isEqualTo("main");
    assertThat(projects.all()).hasSize(1);
    assertThat(tickets.get(ticketId)).isEqualTo(before);
    assertThat(tickets.history(ticketId)).isEqualTo(history);
    assertThat(builds.recent(p.id())).hasSize(1);
    mvc.perform(get("/projects/" + p.id()))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Existing ticket")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Connect GitHub</a>"))));
    mvc.perform(
            post("/projects/" + p.id() + "/connect")
                .session(session)
                .param("_csrf", csrf(session))
                .param("repository", "owner/other"))
        .andExpect(redirectedUrl("/projects/" + p.id()))
        .andExpect(
            flash().attribute("notice", org.hamcrest.Matchers.containsString("already connected")));
    assertThat(projects.get(p.id())).isEqualTo(connected);
  }

  @Test
  void rejectsRepositoriesAssignedToActiveOrArchivedProjectsWithoutMergingData() throws Exception {
    var local = projects.createLocal("Same name");
    var other = projects.connect(100, 42, "Same name", "owner/example", "main");
    long ticketId = tickets.create(local.id(), "Keep local", "", "", "MEDIUM");
    var session = session();
    for (boolean archived : new boolean[] {false, true}) {
      projects.setArchived(other.id(), archived);
      discovery();
      mvc.perform(
              post("/projects/" + local.id() + "/connect")
                  .session(session)
                  .param("_csrf", csrf(session))
                  .param("repository", "owner/example"))
          .andExpect(redirectedUrl("/projects/" + local.id()))
          .andExpect(
              flash()
                  .attribute(
                      "notice",
                      org.hamcrest.Matchers.containsString(
                          "already connected to another project")));
      assertThat(projects.get(local.id())).isEqualTo(local);
      assertThat(projects.byRepository(100, 42).id()).isEqualTo(other.id());
      assertThat(tickets.get(ticketId).projectId()).isEqualTo(local.id());
      assertThat(projects.all()).hasSize(2);
      githubServer.verify();
      githubServer.reset();
    }
  }

  @Test
  void githubFailureAndInvalidInputLeaveProjectLocal() throws Exception {
    var p = projects.createLocal("Keep me");
    var session = session();
    mvc.perform(
            post("/projects/" + p.id() + "/connect")
                .session(session)
                .param("_csrf", csrf(session))
                .param("repository", "not a repository"))
        .andExpect(redirectedUrl("/projects/" + p.id() + "/connect"))
        .andExpect(
            flash().attribute("notice", "Enter a valid repository such as owner/repository."));
    githubServer
        .expect(requestTo("https://api.github.com/repos/owner/example/installation"))
        .andRespond(withResourceNotFound().body("sensitive upstream response"));
    mvc.perform(
            post("/projects/" + p.id() + "/connect")
                .session(session)
                .param("_csrf", csrf(session))
                .param("repository", "owner/example"))
        .andExpect(redirectedUrl("/projects/" + p.id() + "/connect"))
        .andExpect(
            flash().attribute("notice", org.hamcrest.Matchers.containsString("GitHub HTTP 404")));
    assertThat(projects.get(p.id())).isEqualTo(p);
    assertThat(projects.all()).hasSize(1);
  }

  @Test
  void buildRefreshFailureKeepsConnectionForRetry() throws Exception {
    var p = projects.createLocal("Keep connection");
    var session = session();
    discovery();
    sync(true);
    mvc.perform(
            post("/projects/" + p.id() + "/connect")
                .session(session)
                .param("_csrf", csrf(session))
                .param("repository", "owner/example"))
        .andExpect(redirectedUrl("/projects/" + p.id()))
        .andExpect(
            flash()
                .attribute(
                    "notice",
                    "Repository connected. Build refresh failed; you can retry from the board."));
    assertThat(projects.get(p.id()).connected()).isTrue();
    assertThat(projects.get(p.id()).syncError()).isNotBlank();
    assertThat(builds.recent(p.id())).isEmpty();
  }

  @Test
  void connectionRequiresSessionCsrfAndAnExistingActiveLocalProject() throws Exception {
    var p = projects.createLocal("Protected");
    var first = session();
    var second = session();
    mvc.perform(post("/projects/" + p.id() + "/connect").param("repository", "owner/example"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/projects/" + p.id() + "/connect")
                .session(second)
                .param("_csrf", csrf(first))
                .param("repository", "owner/example"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/projects/999999/connect")).andExpect(status().isNotFound());
    mvc.perform(
            post("/projects/999999/connect")
                .session(first)
                .param("_csrf", csrf(first))
                .param("repository", "owner/example"))
        .andExpect(status().isNotFound());
    projects.setArchived(p.id(), true);
    mvc.perform(get("/projects/" + p.id() + "/connect"))
        .andExpect(redirectedUrl("/projects/" + p.id()));
    mvc.perform(
            post("/projects/" + p.id() + "/connect")
                .session(first)
                .param("_csrf", csrf(first))
                .param("repository", "owner/example"))
        .andExpect(redirectedUrl("/projects/" + p.id()))
        .andExpect(
            flash().attribute("notice", "Restore this project before connecting a repository."));
    assertThat(projects.get(p.id()).connected()).isFalse();
  }

  @Test
  void conditionalAttachCannotOverwriteAConnectionOrAttachAfterArchiving() {
    var p = projects.createLocal("First");
    projects.attachRepository(p.id(), 100, 42, "owner/example", "main");
    assertThatThrownBy(() -> projects.attachRepository(p.id(), 101, 42, "owner/other", "main"))
        .isInstanceOf(ApplicationException.class);
    assertThat(projects.get(p.id()).repositoryId()).isEqualTo(100L);
    var archived = projects.createLocal("Archived");
    projects.setArchived(archived.id(), true);
    assertThatThrownBy(
            () -> projects.attachRepository(archived.id(), 101, 42, "owner/other", "main"))
        .isInstanceOf(ApplicationException.class);
    assertThat(projects.get(archived.id()).connected()).isFalse();
  }

  private MockHttpSession session() throws Exception {
    return (MockHttpSession)
        mvc.perform(get("/projects/new")).andReturn().getRequest().getSession();
  }

  private String csrf(MockHttpSession session) {
    return (String) session.getAttribute("csrf");
  }

  private void discovery() {
    githubServer
        .expect(requestTo("https://api.github.com/repos/owner/example/installation"))
        .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));
    token();
    githubServer
        .expect(requestTo("https://api.github.com/repos/owner/example"))
        .andExpect(header("Authorization", "Bearer test-token"))
        .andRespond(
            withSuccess(
                "{\"id\":100,\"name\":\"example\",\"full_name\":\"owner/example\",\"default_branch\":\"main\"}",
                MediaType.APPLICATION_JSON));
  }

  private void token() {
    githubServer
        .expect(requestTo("https://api.github.com/app/installations/42/access_tokens"))
        .andRespond(withSuccess("{\"token\":\"test-token\"}", MediaType.APPLICATION_JSON));
  }

  private void sync(boolean fail) {
    token();
    var request =
        githubServer.expect(
            requestTo("https://api.github.com/repos/owner/example/actions/runs?per_page=30"));
    if (fail) request.andRespond(withForbiddenRequest());
    else
      request.andRespond(
          withSuccess(
              "{\"workflow_runs\":[{\"id\":123,\"name\":\"CI\",\"head_branch\":\"main\",\"head_sha\":\"abcdef123456\",\"status\":\"completed\",\"conclusion\":\"success\",\"html_url\":\"https://github.com/owner/example/actions/runs/123\",\"run_number\":1,\"run_attempt\":1,\"updated_at\":\"2026-11-08T00:00:00Z\"}]}",
              MediaType.APPLICATION_JSON));
  }
}
