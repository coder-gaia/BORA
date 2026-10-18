package dev.bora;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bora.routing.GeoPoint;
import dev.bora.routing.Route;
import dev.bora.routing.RoutingService;
import dev.bora.routing.RoutingTools;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.core.ApplicationModules;
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
class BoraApplicationIT {

  private static final String ROUTING_MANIFEST = "routing/sp-fixture-manifest.json";
  private static final Path ROUTING_GRAPHS = Path.of("target", "routing-app-it").toAbsolutePath();

  @DynamicPropertySource
  static void routingProperties(DynamicPropertyRegistry registry) {
    RoutingTools.prepareGraph(ROUTING_MANIFEST, ROUTING_GRAPHS);
    registry.add("bora.routing.graph-root", ROUTING_GRAPHS::toString);
    registry.add("bora.routing.manifest", () -> ROUTING_MANIFEST);
  }

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @Autowired JdbcClient jdbc;
  @Autowired RoutingService routing;
  @Autowired Environment environment;

  @LocalServerPort int port;

  @Test
  void keepsDomainModulesClosed() {
    ApplicationModules.of(BoraApplication.class).verify();
  }

  @Test
  void appliesMigrationAndExecutesSpatialQueries() {
    assertThat(
            jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '1'")
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(jdbc.sql("SELECT postgis_lib_version()").query(String.class).single())
        .isEqualTo("3.6.4");
    assertThat(
            jdbc.sql(
                    """
                    SELECT ST_DWithin(
                      ST_SetSRID(ST_MakePoint(-46.6333, -23.5505), 4326)::geography,
                      ST_SetSRID(ST_MakePoint(-46.6334, -23.5506), 4326)::geography,
                      50
                    )
                    """)
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void readinessIncludesDatabaseAndRoutingWithoutExposingDetails() throws Exception {
    HttpResponse<String> response = get("/actuator/health/readiness");
    JsonNode health = JsonMapper.builder().build().readTree(response.body());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(health.path("status").asString()).isEqualTo("UP");
    assertThat(health.path("components").path("db").path("status").asString()).isEqualTo("UP");
    assertThat(health.path("components").path("db").has("details")).isFalse();
    assertThat(health.path("components").path("routing").path("status").asString()).isEqualTo("UP");
    assertThat(health.path("components").path("routing").has("details")).isFalse();
  }

  @Test
  void livenessExcludesDatabaseAndRouting() throws Exception {
    HttpResponse<String> response = get("/actuator/health/liveness");
    JsonNode health = JsonMapper.builder().build().readTree(response.body());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(health.path("status").asString()).isEqualTo("UP");
    assertThat(health.path("components").has("db")).isFalse();
    assertThat(health.path("components").has("routing")).isFalse();
  }

  @Test
  void calculatesRouteAndAcceleratedEtaOnRealSaoPauloWays() throws Exception {
    try (InputStream input = getClass().getClassLoader().getResourceAsStream(ROUTING_MANIFEST)) {
      assertThat(input).isNotNull();
      JsonNode pair = JsonMapper.builder().build().readTree(input).path("warmupPairs").get(0);
      GeoPoint origin = point(pair.path("origin"));
      GeoPoint destination = point(pair.path("destination"));

      Route route = routing.route(origin, destination);
      double speed = environment.getRequiredProperty("bora.routing.simulation-speed", Double.class);

      assertThat(route.distanceMeters()).isPositive();
      assertThat(route.duration()).isPositive();
      assertThat(route.simulatedDuration().toMillis())
          .isEqualTo((long) Math.ceil(route.duration().toMillis() / speed));
      assertThat(route.geometry()).hasSizeGreaterThan(1);
      assertThat(route.origin().requested()).isEqualTo(origin);
      assertThat(route.destination().requested()).isEqualTo(destination);
      assertThat(route.geometry().getFirst()).isEqualTo(route.origin().matched());
      assertThat(route.geometry().getLast()).isEqualTo(route.destination().matched());
    }
  }

  private static GeoPoint point(JsonNode value) {
    return new GeoPoint(value.path("latitude").asDouble(), value.path("longitude").asDouble());
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
