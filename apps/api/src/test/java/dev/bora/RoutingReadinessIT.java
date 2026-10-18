package dev.bora;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutingReadinessIT {

  private static final Path MISSING_GRAPH_ROOT =
      Path.of("target", "missing-routing-" + UUID.randomUUID()).toAbsolutePath();

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void missingGraph(DynamicPropertyRegistry registry) {
    registry.add("bora.routing.graph-root", MISSING_GRAPH_ROOT::toString);
    registry.add("bora.routing.manifest", () -> "routing/sp-fixture-manifest.json");
  }

  @LocalServerPort int port;

  @Test
  void keepsReadinessDownWithoutImportingOrExposingGraphDetails() throws Exception {
    HttpResponse<String> response = get("/actuator/health/readiness");
    JsonNode health = JsonMapper.builder().build().readTree(response.body());

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(health.path("status").asString()).isEqualTo("DOWN");
    assertThat(health.path("components").path("db").path("status").asString()).isEqualTo("UP");
    assertThat(health.path("components").path("routing").path("status").asString())
        .isEqualTo("DOWN");
    assertThat(health.path("components").path("routing").has("details")).isFalse();
    assertThat(response.body()).doesNotContain(MISSING_GRAPH_ROOT.toString());
    assertThat(MISSING_GRAPH_ROOT).doesNotExist();
  }

  @Test
  void keepsLivenessUpWhileGraphIsMissing() throws Exception {
    HttpResponse<String> response = get("/actuator/health/liveness");
    JsonNode health = JsonMapper.builder().build().readTree(response.body());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(health.path("status").asString()).isEqualTo("UP");
    assertThat(health.path("components").has("routing")).isFalse();
    assertThat(MISSING_GRAPH_ROOT).doesNotExist();
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
