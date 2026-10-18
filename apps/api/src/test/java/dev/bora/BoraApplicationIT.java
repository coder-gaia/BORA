package dev.bora;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.core.ApplicationModules;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BoraApplicationIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @Autowired JdbcClient jdbc;

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
  void readinessIncludesDatabaseWithoutExposingDetails() throws Exception {
    HttpResponse<String> response = get("/actuator/health/readiness");
    JsonNode health = JsonMapper.builder().build().readTree(response.body());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(health.path("status").asString()).isEqualTo("UP");
    assertThat(health.path("components").path("db").path("status").asString()).isEqualTo("UP");
    assertThat(health.path("components").path("db").has("details")).isFalse();
  }

  @Test
  void livenessDoesNotDependOnDatabase() throws Exception {
    HttpResponse<String> response = get("/actuator/health/liveness");
    JsonNode health = JsonMapper.builder().build().readTree(response.body());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(health.path("status").asString()).isEqualTo("UP");
    assertThat(health.path("components").has("db")).isFalse();
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
