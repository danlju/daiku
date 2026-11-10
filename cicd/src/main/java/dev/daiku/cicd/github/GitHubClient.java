package dev.daiku.cicd.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GitHubClient {
  private final String appId, keyPath, apiUrl;
  private final ObjectMapper json;
  private final RestClient http;

  @Autowired
  public GitHubClient(
      @Value("${daiku.github.app-id}") String appId,
      @Value("${daiku.github.private-key-path}") String keyPath,
      @Value("${daiku.github.api-url}") String apiUrl,
      ObjectMapper json) {
    this(appId, keyPath, apiUrl, json, httpBuilder());
  }

  GitHubClient(
      String appId, String keyPath, String apiUrl, ObjectMapper json, RestClient.Builder builder) {
    this.appId = appId;
    this.keyPath = keyPath;
    this.apiUrl = apiUrl;
    this.json = json;
    this.http = builder.baseUrl(apiUrl).build();
  }

  private static RestClient.Builder httpBuilder() {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    factory.setReadTimeout(Duration.ofSeconds(15));
    return RestClient.builder().requestFactory(factory);
  }

  public boolean configured() {
    return !appId.isBlank() && !keyPath.isBlank();
  }

  public JsonNode installation(String repo) {
    return request("GET", "/repos/" + repo + "/installation", jwt(), null, Operation.INSTALLATION);
  }

  public String token(long installationId) {
    return request(
            "POST",
            "/app/installations/" + installationId + "/access_tokens",
            jwt(),
            "{}",
            Operation.TOKEN)
        .path("token")
        .asText();
  }

  public JsonNode repository(String repo, String token) {
    return request("GET", "/repos/" + repo, token, null, Operation.REPOSITORY);
  }

  public JsonNode runs(String repo, String token) {
    return request(
            "GET", "/repos/" + repo + "/actions/runs?per_page=30", token, null, Operation.RUNS)
        .path("workflow_runs");
  }

  public JsonNode runsForCommit(String repo, String token, String sha) {
    return filteredRuns(repo, token, "head_sha", sha);
  }

  public JsonNode runsForBranch(String repo, String token, String branch) {
    return filteredRuns(repo, token, "branch", branch);
  }

  private JsonNode filteredRuns(String repo, String token, String parameter, String value) {
    var uri =
        UriComponentsBuilder.fromUriString(apiUrl)
            .path("/repos/" + repo + "/actions/runs")
            .queryParam("per_page", 30)
            .queryParam(parameter, "{filter}")
            .encode()
            .buildAndExpand(value)
            .toUri();
    return request("GET", uri, token, null, Operation.RUNS).path("workflow_runs");
  }

  public JsonNode pullRequest(String repo, long number, String token) {
    return request(
        "GET", "/repos/" + repo + "/pulls/" + number, token, null, Operation.PULL_REQUEST);
  }

  private enum Operation {
    PULL_REQUEST(
        "read the linked pull request",
        "Check the PR number and the installation's Pull requests read permission. Approve any"
            + " pending permission updates."),
    INSTALLATION(
        "find the repository's App installation",
        "Check the owner/repository spelling and install this GitHub App on the account that owns"
            + " the repository. In the installation's Repository access settings, include this"
            + " repository."),
    TOKEN(
        "create an installation access token",
        "Check that the GitHub App installation still exists and is not suspended."),
    REPOSITORY(
        "read repository metadata",
        "Check the installation's Repository access selection and its Metadata read permission."
            + " Approve any pending permission updates."),
    RUNS(
        "read Actions workflow runs",
        "Check that the installation has Actions read permission and approve any pending permission"
            + " updates.");

    private final String description;
    private final String guidance;

    Operation(String description, String guidance) {
      this.description = description;
      this.guidance = guidance;
    }
  }

  private GitHubAccessException httpFailure(Operation operation, int status) {
    String guidance;
    if (status == 401) {
      guidance =
          operation == Operation.INSTALLATION || operation == Operation.TOKEN
              ? "Check that GITHUB_APP_ID and the RSA private key belong to the same GitHub App,"
                  + " and that your system clock is correct."
              : "The installation access token was rejected. Retry and check the GitHub App"
                  + " installation.";
    } else if (status == 429) {
      guidance = "GitHub rate limited the request. Wait before retrying.";
    } else if (status >= 500) {
      guidance = "GitHub is temporarily unavailable. Retry later.";
    } else {
      guidance = operation.guidance;
      if (status == 403)
        guidance +=
            " Organization restrictions, a suspended installation, or GitHub rate limits may also"
                + " block access.";
    }
    return new GitHubAccessException(
        "Could not " + operation.description + " (GitHub HTTP " + status + "). " + guidance);
  }

  private JsonNode request(
      String method, String path, String token, String body, Operation operation) {
    return request(method, URI.create(apiUrl + path), token, body, operation);
  }

  private JsonNode request(String method, URI uri, String token, String body, Operation operation) {
    try {
      var builder =
          http.method(org.springframework.http.HttpMethod.valueOf(method))
              .uri(uri)
              .header("Authorization", "Bearer " + token)
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", "2022-11-28")
              .header("User-Agent", "Daiku/0.1");
      if (body != null)
        builder.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
      return json.readTree(builder.retrieve().body(String.class));
    } catch (RestClientResponseException e) {
      throw httpFailure(operation, e.getStatusCode().value());
    } catch (RestClientException e) {
      throw new GitHubAccessException(
          "Could not "
              + operation.description
              + ". Cannot reach GitHub. Check network connectivity and the configured GitHub API"
              + " URL, then retry.");
    } catch (java.io.IOException e) {
      throw new GitHubAccessException(
          "Could not "
              + operation.description
              + ". GitHub returned an unreadable response. Retry later.");
    }
  }

  private String jwt() {
    if (!configured())
      throw new GitHubAccessException(
          "Configure GITHUB_APP_ID and GITHUB_PRIVATE_KEY_PATH, then restart Daiku.");
    try {
      String pem = Files.readString(Path.of(keyPath));
      // GitHub downloads PKCS#1 keys; Java consumes PKCS#8. Wrap the RSA key when needed.
      byte[] der =
          Base64.getMimeDecoder()
              .decode(pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
      if (pem.contains("BEGIN RSA PRIVATE KEY")) {
        byte[] algorithm =
            java.util.HexFormat.of().parseHex("020100300d06092a864886f70d0101010500");
        der = sequence(concat(algorithm, tagged((byte) 0x04, der)));
      }
      var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
      long now = Instant.now().getEpochSecond();
      String input =
          encode(json.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")))
              + "."
              + encode(
                  json.writeValueAsBytes(Map.of("iat", now - 60, "exp", now + 540, "iss", appId)));
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(key);
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + encode(signer.sign());
    } catch (Exception e) {
      throw new GitHubAccessException(
          "Cannot load the GitHub App RSA private key. Check that GITHUB_PRIVATE_KEY_PATH points to"
              + " a readable PKCS#1 or PKCS#8 RSA PEM file, then restart Daiku.");
    }
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private static byte[] sequence(byte[] bytes) {
    return tagged((byte) 0x30, bytes);
  }

  private static byte[] tagged(byte tag, byte[] bytes) {
    byte[] length =
        bytes.length < 128
            ? new byte[] {(byte) bytes.length}
            : bytes.length < 256
                ? new byte[] {(byte) 0x81, (byte) bytes.length}
                : new byte[] {(byte) 0x82, (byte) (bytes.length >> 8), (byte) bytes.length};
    return concat(concat(new byte[] {tag}, length), bytes);
  }
}
