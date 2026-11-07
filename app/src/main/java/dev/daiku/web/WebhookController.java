package dev.daiku.web;

import dev.daiku.cicd.github.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class WebhookController {
  private final WebhookService webhooks;

  public WebhookController(WebhookService webhooks) {
    this.webhooks = webhooks;
  }

  @PostMapping("/webhooks/github")
  public ResponseEntity<Void> receive(
      HttpServletRequest request,
      @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery,
      @RequestHeader(value = "X-GitHub-Event", required = false) String event,
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature)
      throws java.io.IOException {
    byte[] payload = request.getInputStream().readNBytes(2_000_001);
    if (payload.length > 2_000_000)
      throw new ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
    webhooks.receive(delivery, event, signature, payload);
    return ResponseEntity.accepted().build();
  }
}
