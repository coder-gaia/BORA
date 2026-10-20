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
              "/api/v1/map/snapshot",
              "/api/v1/map/config",
              "/api/v1/ops/snapshot",
              "/api/v1/tracking/{code}",
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
              "/api/v1/courier/training-order",
              "/api/v1/courier/training-order/{id}/cancel",
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
      JsonNode training = paths.path("/api/v1/courier/training-order").path("post");
      JsonNode endTraining = paths.path("/api/v1/courier/training-order/{id}/cancel").path("post");
      assertThat(training.path("operationId").asString()).isEqualTo("createCourierTrainingOrder");
      assertThat(endTraining.path("operationId").asString())
          .isEqualTo("cancelCourierTrainingOrder");
      assertThat(training.path("responses").has("201")).isTrue();
      assertThat(endTraining.path("responses").has("200")).isTrue();
      assertThat(
              training
                  .path("responses")
                  .path("201")
                  .path("content")
                  .path("application/json")
                  .path("schema")
                  .path("$ref")
                  .asString())
          .endsWith("/TrainingOrderReceipt");
      assertThat(
              endTraining
                  .path("responses")
                  .path("200")
                  .path("content")
                  .path("application/json")
                  .path("schema")
                  .path("$ref")
                  .asString())
          .endsWith("/TrainingOrderState");
      for (String status : List.of("400", "401", "403", "409", "422", "429", "503")) {
        assertThat(training.path("responses").has(status))
            .as("training create %s", status)
            .isTrue();
        assertThat(
                training
                    .path("responses")
                    .path(status)
                    .path("content")
                    .path("application/problem+json")
                    .path("schema")
                    .path("$ref")
                    .asString())
            .isEqualTo("#/components/schemas/ApiProblem");
      }
      for (String status : List.of("400", "401", "403", "404", "409")) {
        assertThat(endTraining.path("responses").has(status))
            .as("training cancel %s", status)
            .isTrue();
        assertThat(
                endTraining
                    .path("responses")
                    .path(status)
                    .path("content")
                    .path("application/problem+json")
                    .path("schema")
                    .path("$ref")
                    .asString())
            .isEqualTo("#/components/schemas/ApiProblem");
      }
      assertThat(training.path("responses").path("429").path("headers").has("Retry-After"))
          .isTrue();
      for (JsonNode operation : List.of(training, endTraining)) {
        assertThat(operation.path("security").size()).isEqualTo(1);
        assertThat(operation.path("security").get(0).has("sessionCookie")).isTrue();
        assertThat(operation.path("security").get(0).has("csrfToken")).isTrue();
      }
      boolean trainingKeyRequired = false;
      for (JsonNode parameter : training.path("parameters")) {
        if (parameter.path("name").asString().equals("Idempotency-Key")) {
          trainingKeyRequired =
              parameter.path("in").asString().equals("header")
                  && parameter.path("required").asBoolean();
        }
      }
      assertThat(trainingKeyRequired).as("training requires Idempotency-Key header").isTrue();
      JsonNode receiptProperties =
          contract
              .path("components")
              .path("schemas")
              .path("TrainingOrderReceipt")
              .path("properties");
      for (String property :
          List.of(
              "orderId",
              "offerId",
              "expiresAt",
              "confirmationCode",
              "status",
              "version",
              "serverTime")) {
        assertThat(receiptProperties.has(property)).as(property).isTrue();
      }
      assertThat(
              contract
                  .path("components")
                  .path("schemas")
                  .path("TrainingOrderState")
                  .path("properties")
                  .has("confirmationCode"))
          .isFalse();
      assertThat(paths.path("/api/v1/courier/positions").path("post").path("responses").has("202"))
          .isTrue();
      assertThat(paths.path("/api/v1/auth/csrf").path("get").path("parameters").isEmpty()).isTrue();
      assertThat(paths.path("/api/v1/auth/me").path("get").path("security").isEmpty()).isTrue();
      assertThat(paths.path("/api/v1/map/snapshot").path("get").path("security").isEmpty())
          .isTrue();
      assertThat(paths.path("/api/v1/map/config").path("get").path("security").isEmpty()).isTrue();
      assertThat(paths.path("/api/v1/map/config").path("get").path("operationId").asString())
          .isEqualTo("getMapConfig");
      HttpResponse<String> simulation =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/map/config"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(simulation.statusCode()).isEqualTo(200);
      assertThat(mapper.readTree(simulation.body()).path("simulationSpeed").asDouble())
          .isEqualTo(3);
      assertThat(paths.path("/api/v1/tracking/{code}").path("get").path("security").isEmpty())
          .isTrue();
      assertThat(
              paths
                  .path("/api/v1/ops/snapshot")
                  .path("get")
                  .path("security")
                  .get(0)
                  .has("sessionCookie"))
          .isTrue();
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
