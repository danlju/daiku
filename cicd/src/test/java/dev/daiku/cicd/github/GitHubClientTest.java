package dev.daiku.cicd.github;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubClientTest {
  @TempDir Path temp;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void authenticatesAsAppThenInstallationWithPkcs8Key() throws Exception {
    exercise(false);
  }

  @Test
  void acceptsThePkcs1PrivateKeysDownloadedFromGithub() throws Exception {
    exercise(true);
  }

  private void exercise(boolean pkcs1) throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    byte[] encoded =
        pkcs1 ? pkcs1((RSAPrivateCrtKey) pair.getPrivate()) : pair.getPrivate().getEncoded();
    String label = pkcs1 ? "RSA PRIVATE KEY" : "PRIVATE KEY";
    Path pem = temp.resolve("app.pem");
    Files.writeString(
        pem,
        "-----BEGIN "
            + label
            + "-----\n"
            + Base64.getMimeEncoder().encodeToString(encoded)
            + "\n-----END "
            + label
            + "-----\n");
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://api.github.com/repos/owner/example/installation"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(request -> verifyJwt(request.getHeaders().getFirst("Authorization"), pair))
        .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://api.github.com/app/installations/42/access_tokens"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(request -> verifyJwt(request.getHeaders().getFirst("Authorization"), pair))
        .andExpect(content().json("{}"))
        .andRespond(withSuccess("{\"token\":\"installation-token\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://api.github.com/repos/owner/example"))
        .andExpect(header("Authorization", "Bearer installation-token"))
        .andRespond(
            withSuccess(
                "{\"id\":100,\"name\":\"example\",\"full_name\":\"owner/example\",\"default_branch\":\"main\"}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://api.github.com/repos/owner/example/actions/runs?per_page=30"))
        .andExpect(header("Authorization", "Bearer installation-token"))
        .andRespond(withSuccess("{\"workflow_runs\":[{\"id\":1}]}", MediaType.APPLICATION_JSON));
    var client = new GitHubClient("1234", pem.toString(), "https://api.github.com", json, builder);
    long id = client.installation("owner/example").path("id").asLong();
    assertThat(id).isEqualTo(42);
    String token = client.token(id);
    assertThat(client.repository("owner/example", token).path("id").asLong()).isEqualTo(100);
    assertThat(client.runs("owner/example", token)).hasSize(1);
    server.verify();
  }

  @Test
  void githubErrorsDoNotExposeResponseBodiesOrCredentials() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("https://api.github.com/repos/owner/example"))
        .andRespond(withForbiddenRequest().body("sensitive upstream body"));
    var client = new GitHubClient("", "", "https://api.github.com", json, builder);
    assertThatThrownBy(() -> client.repository("owner/example", "secret-token"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("GitHub request failed")
        .hasNoCause();
    server.verify();
  }

  private void verifyJwt(String auth, KeyPair pair) {
    try {
      assertThat(auth).startsWith("Bearer ");
      String[] jwt = auth.substring(7).split("\\.");
      var signer = Signature.getInstance("SHA256withRSA");
      signer.initVerify(pair.getPublic());
      signer.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
      assertThat(signer.verify(Base64.getUrlDecoder().decode(jwt[2]))).isTrue();
      var claims = json.readTree(Base64.getUrlDecoder().decode(jwt[1]));
      assertThat(claims.path("iss").asText()).isEqualTo("1234");
      assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(600);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static byte[] pkcs1(RSAPrivateCrtKey key) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    for (var value :
        new java.math.BigInteger[] {
          java.math.BigInteger.ZERO,
          key.getModulus(),
          key.getPublicExponent(),
          key.getPrivateExponent(),
          key.getPrimeP(),
          key.getPrimeQ(),
          key.getPrimeExponentP(),
          key.getPrimeExponentQ(),
          key.getCrtCoefficient()
        }) out.write(tag(2, value.toByteArray()));
    return tag(48, out.toByteArray());
  }

  private static byte[] tag(int type, byte[] content) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    out.write(type);
    if (content.length < 128) out.write(content.length);
    else if (content.length < 256) {
      out.write(0x81);
      out.write(content.length);
    } else {
      out.write(0x82);
      out.write(content.length >> 8);
      out.write(content.length);
    }
    out.write(content);
    return out.toByteArray();
  }
}
