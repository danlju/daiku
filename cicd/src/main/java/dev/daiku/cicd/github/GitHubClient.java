package dev.daiku.cicd.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    return request("GET", "/repos/" + repo + "/installation", jwt(), null);
  }

  public String token(long installationId) {
    return request("POST", "/app/installations/" + installationId + "/access_tokens", jwt(), "{}")
        .path("token")
        .asText();
  }

  public JsonNode repository(String repo, String token) {
    return request("GET", "/repos/" + repo, token, null);
  }

  public JsonNode runs(String repo, String token) {
    return request("GET", "/repos/" + repo + "/actions/runs?per_page=30", token, null)
        .path("workflow_runs");
  }

  private JsonNode request(String method, String path, String token, String body) {
    try {
      var builder =
          http.method(org.springframework.http.HttpMethod.valueOf(method))
              .uri(path)
              .header("Authorization", "Bearer " + token)
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", "2022-11-28")
              .header("User-Agent", "Daiku/0.1");
      if (body != null)
        builder.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
      return json.readTree(builder.retrieve().body(String.class));
    } catch (RestClientException | java.io.IOException e) {
      throw new IllegalStateException("GitHub request failed");
    }
  }

  private String jwt() {
    if (!configured())
      throw new IllegalStateException("Configure the GitHub App ID and private key first");
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
      throw new IllegalStateException("Cannot load the GitHub App RSA private key");
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
