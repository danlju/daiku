package dev.daiku.cicd.github;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.daiku.cicd.builds.BuildStore;
import dev.daiku.core.port.ProjectRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebhookService {
  private final String secret;
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final ProjectRepository projects;
  private final BuildStore builds;

  public WebhookService(
      @Value("${daiku.github.webhook-secret}") String secret,
      JdbcTemplate db,
      ObjectMapper json,
      ProjectRepository projects,
      BuildStore builds) {
    this.secret = secret;
    this.db = db;
    this.json = json;
    this.projects = projects;
    this.builds = builds;
  }

  public void receive(String delivery, String event, String signature, byte[] payload) {
    if (secret.isBlank())
      throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Webhook secret is not configured");
    if (!validSignature(signature, payload))
      throw new ResponseStatusException(UNAUTHORIZED, "Invalid webhook signature");
    if (delivery == null || !delivery.matches("[A-Za-z0-9-]{1,100}"))
      throw new ResponseStatusException(BAD_REQUEST, "Invalid delivery ID");
    if (!"workflow_run".equals(event)) return;
    try {
      var node = json.readTree(payload);
      if (!node.path("workflow_run").isObject()
          || node.path("repository").path("id").asLong() <= 0
          || node.path("installation").path("id").asLong() <= 0)
        throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new ResponseStatusException(BAD_REQUEST, "Invalid webhook payload");
    }
    try {
      db.update(
          "INSERT INTO webhook_deliveries(delivery_id,payload) VALUES (?,?)",
          delivery,
          new String(payload, StandardCharsets.UTF_8));
    } catch (org.springframework.dao.DuplicateKeyException ignored) {
      /* Redelivery already recorded. */
    }
  }

  private boolean validSignature(String signature, byte[] payload) {
    try {
      if (signature == null || !signature.matches("sha256=[0-9a-f]{64}")) return false;
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return MessageDigest.isEqual(
          mac.doFinal(payload), HexFormat.of().parseHex(signature.substring(7)));
    } catch (Exception e) {
      return false;
    }
  }

  @Scheduled(fixedDelay = 5000, initialDelay = 5000)
  public void processPending() {
    // One application instance in increment one. Each ingest is independently idempotent.
    var pending =
        db.queryForList(
            "SELECT delivery_id,payload,attempts FROM webhook_deliveries WHERE state='PENDING' AND"
                + " next_attempt_at<=CURRENT_TIMESTAMP ORDER BY received_at LIMIT 25");
    for (var row : pending) {
      String id = (String) row.get("delivery_id");
      int attempts = ((Number) row.get("attempts")).intValue() + 1;
      try {
        var node = json.readTree((String) row.get("payload"));
        var project =
            projects.byRepository(
                node.path("repository").path("id").asLong(),
                node.path("installation").path("id").asLong());
        if (project != null && !project.archived())
          builds.ingest(project, node.path("workflow_run"));
        db.update(
            "UPDATE webhook_deliveries SET state='PROCESSED',attempts=?,payload='' WHERE"
                + " delivery_id=?",
            attempts,
            id);
      } catch (Exception e) {
        db.update(
            "UPDATE webhook_deliveries SET state=?,attempts=?,next_attempt_at=? WHERE"
                + " delivery_id=?",
            attempts >= 5 ? "FAILED" : "PENDING",
            attempts,
            java.time.OffsetDateTime.now().plusSeconds(30L * attempts),
            id);
      }
    }
  }
}
