package dev.bora;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bora.routing.RoutingTools;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
class OpenApiContractIT {

  private static final String MANIFEST = "routing/sp-fixture-manifest.json";
  private static final Path GRAPH_ROOT = Path.of("target", "routing-openapi-it").toAbsolutePath();

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void routingProperties(DynamicPropertyRegistry registry) {
    RoutingTools.prepareGraph(MANIFEST, GRAPH_ROOT);
    registry.add("bora.routing.graph-root", GRAPH_ROOT::toString);
    registry.add("bora.routing.manifest", () -> MANIFEST);
  }

  @LocalServerPort int port;

  @Test
  void exportsActualControllerContractAndRejectsAnOutdatedSnapshot() throws Exception {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/openapi"))
                  .timeout(Duration.ofSeconds(20))
                  .header("Accept", "application/json")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      Files.writeString(Path.of("target", "openapi-raw.json"), response.body());
      JsonMapper mapper = JsonMapper.builder().build();
      JsonNode contract = mapper.readTree(response.body());
      assertThat(contract.path("openapi").asString()).startsWith("3.");
      for (String endpoint :
          List.of(
              "/api/v1/auth/me",
              "/api/v1/auth/csrf",
              "/api/v1/auth/demo",
              "/api/v1/auth/logout",
              "/api/v1/stores/me",
              "/api/v1/orders",
              "/api/v1/orders/{id}",
              "/api/v1/orders/{id}/cancel",
              "/api/v1/courier/me",
              "/api/v1/courier/availability",
              "/api/v1/courier/positions",
              "/api/v1/courier/work",
              "/api/v1/courier/route",
              "/api/v1/courier/offer",
              "/api/v1/offers/{id}/accept",
              "/api/v1/offers/{id}/reject",
              "/api/v1/ops/orders/{id}/reassign",
              "/api/v1/orders/{id}/pickup",
              "/api/v1/orders/{id}/deliver")) {
        assertThat(contract.path("paths").has(endpoint)).as(endpoint).isTrue();
      }
      JsonNode paths = contract.path("paths");
      for (String endpoint :
          List.of(
              "/api/v1/offers/{id}/accept",
              "/api/v1/offers/{id}/reject",
              "/api/v1/ops/orders/{id}/reassign")) {
        assertThat(paths.path(endpoint).path("post").path("responses").has("204"))
            .as(endpoint)
            .isTrue();
      }
      assertThat(paths.path("/api/v1/orders").path("post").path("responses").has("201")).isTrue();
      assertThat(paths.path("/api/v1/courier/positions").path("post").path("responses").has("202"))
          .isTrue();
      assertThat(paths.path("/api/v1/auth/csrf").path("get").path("parameters").isEmpty()).isTrue();
      assertThat(paths.path("/api/v1/auth/me").path("get").path("security").isEmpty()).isTrue();
      JsonNode security = paths.path("/api/v1/orders").path("post").path("security");
      assertThat(security.size()).isEqualTo(1);
      assertThat(security.get(0).has("sessionCookie")).isTrue();
      assertThat(security.get(0).has("csrfToken")).isTrue();
      assertThat(
              contract
                  .path("components")
                  .path("schemas")
                  .path("SessionView")
                  .path("properties")
                  .path("user")
                  .path("anyOf")
                  .get(1)
                  .path("type")
                  .asString())
          .isEqualTo("null");
      assertThat(
              contract
                  .path("components")
                  .path("schemas")
                  .path("SessionView")
                  .path("properties")
                  .path("user")
                  .path("anyOf")
                  .get(0)
                  .has("type"))
          .isFalse();
      Object raw = mapper.readValue(response.body(), Object.class);
      if (raw instanceof Map<?, ?> root) {
        root.remove("servers");
        if (root.get("tags") instanceof List<?> tags) {
          tags.sort(
              Comparator.comparing(
                  tag -> tag instanceof Map<?, ?> values ? values.get("name").toString() : ""));
        }
      }
      String canonical =
          mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sort(raw)) + "\n";
      Path exported = Path.of("target", "openapi.json");
      Files.createDirectories(exported.getParent());
      Files.writeString(exported, canonical);
      Path snapshot = repositoryRoot().resolve("docs/api/openapi.json");
      if (Boolean.getBoolean("bora.openapi.update")) {
        Files.createDirectories(snapshot.getParent());
        Files.writeString(snapshot, canonical);
      }
      assertThat(snapshot).exists();
      assertThat(mapper.readTree(Files.readString(snapshot)))
          .as("Atualize o contrato com -Dbora.openapi.update=true e regenere os tipos TypeScript.")
          .isEqualTo(mapper.readTree(canonical));
    }
  }

  private static Object sort(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach(
          (key, child) -> {
            if (("required".equals(key) || "enum".equals(key) || "type".equals(key))
                && child instanceof List<?> items) {
              sorted.put(
                  key.toString(),
                  items.stream().sorted(Comparator.comparing(String::valueOf)).toList());
            } else {
              sorted.put(key.toString(), sort(child));
            }
          });
      return sorted;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(OpenApiContractIT::sort).toList();
    }
    return value;
  }

  private static Path repositoryRoot() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null && !Files.exists(directory.resolve("scripts/check-rules.mjs"))) {
      directory = directory.getParent();
    }
    if (directory == null) {
      throw new IllegalStateException("A raiz do repositório não foi encontrada.");
    }
    return directory;
  }
}
