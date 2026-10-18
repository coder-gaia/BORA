package dev.bora.orders.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.bora.demo.DemoLimits;
import dev.bora.orders.OrderConfirmationCodes;
import dev.bora.routing.GeoPoint;
import dev.bora.routing.Route;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingService;
import dev.bora.routing.SnappedPoint;
import dev.bora.shared.ApiException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OrdersApiIT.RoutingTestConfiguration.class)
class OrdersApiIT {
  private static final String ORDERS = "/api/v1/orders";
  private static final String BODY = "{\"dropoff\":{\"latitude\":-23.559,\"longitude\":-46.650}}";

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @Autowired RoutingService routing;
  @MockitoBean DemoLimits limits;
  @MockitoSpyBean JdbcClient jdbc;
  @Autowired OrderLifecycleService lifecycle;
  @Autowired OrderConfirmationCodes codes;
  @Autowired JsonMapper mapper;
  @LocalServerPort int port;

  @BeforeEach
  void availableRouting() {
    reset(routing);
    doAnswer(
            call -> {
              GeoPoint origin = call.getArgument(0);
              GeoPoint destination = call.getArgument(1);
              return new Route(
                  List.of(origin, destination),
                  1_000,
                  Duration.ofSeconds(120),
                  Duration.ofSeconds(40),
                  new SnappedPoint(origin, origin, 0),
                  new SnappedPoint(destination, destination, 0));
            })
        .when(routing)
        .route(any(), any());
  }

  @Test
  void createsCancelsAndReplaysOriginalResponseWithoutPersistingClearConfirmation()
      throws Exception {
    Browser browser = new Browser();
    browser.login("STORE");
    var created = browser.post(ORDERS, BODY, "create-cancel");
    assertThat(created.statusCode()).isEqualTo(201);
    JsonNode original = json(created);
    UUID id = UUID.fromString(original.path("id").asString());
    assertThat(created.headers().firstValue("Location")).contains(ORDERS + "/" + id);
    assertThat(original.path("status").asString()).isEqualTo("SEARCHING");
    assertThat(original.path("version").asLong()).isZero();
    assertThat(original.path("publicCode").asString()).matches("[A-Za-z0-9_-]{32}");
    assertThat(original.path("confirmationCode").asString()).matches("[0-9]{6}");
    assertThat(original.path("timeline")).hasSize(1);
    assertThat(original.path("timeline").get(0).path("type").asString()).isEqualTo("ORDER_CREATED");
    String hash =
        jdbc.sql("SELECT confirmation_code_hash FROM orders WHERE id=:id")
            .param("id", id)
            .query(String.class)
            .single();
    assertThat(codes.verify(id, original.path("confirmationCode").asString(), hash)).isTrue();
    String snapshot =
        jdbc.sql("SELECT response_json::text FROM order_idempotency WHERE order_id=:id")
            .param("id", id)
            .query(String.class)
            .single();
    assertThat(snapshot).doesNotContain("confirmationCode");

    var canceled = browser.post(ORDERS + "/" + id + "/cancel", "{\"expectedVersion\":0}", null);
    assertThat(canceled.statusCode()).isEqualTo(200);
    assertThat(json(canceled).path("status").asString()).isEqualTo("CANCELED");
    assertThat(json(canceled).path("version").asLong()).isEqualTo(1);
    assertThat(json(canceled).path("timeline")).hasSize(2);
    assertThat(json(browser.post(ORDERS, BODY, "create-cancel"))).isEqualTo(original);
    assertThat(json(browser.get(ORDERS + "/" + id)).path("status").asString())
        .isEqualTo("CANCELED");
    assertThat(
            browser
                .post(ORDERS + "/" + id + "/cancel", "{\"expectedVersion\":0}", null)
                .statusCode())
        .isEqualTo(409);
    assertThat(countEvents(id)).isEqualTo(2);
    UUID context = contextOf(browser.userId);
    verify(limits, times(1)).checkOrderCreation(context);
    assertThat(
            browser
                .post(
                    ORDERS,
                    "{\"dropoff\":{\"latitude\":-23.558,\"longitude\":-46.650}}",
                    "create-cancel")
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void keepsVisitorOrdersPrivateAcrossRolesAndAllowsSameContextOperations() throws Exception {
    Browser owner = new Browser();
    owner.login("STORE");
    JsonNode order = json(owner.post(ORDERS, BODY, "private-order"));
    String id = order.path("id").asString();
    Browser outsider = new Browser();
    outsider.login("OPERATIONS");
    assertThat(outsider.get(ORDERS + "/" + id).statusCode()).isEqualTo(404);
    assertThat(
            outsider
                .post(ORDERS + "/" + id + "/cancel", "{\"expectedVersion\":0}", null)
                .statusCode())
        .isEqualTo(404);
    assertThat(json(outsider.get(ORDERS)).path("items").toString()).doesNotContain(id);
    owner.login("OPERATIONS");
    var detail = owner.get(ORDERS + "/" + id);
    assertThat(detail.statusCode()).isEqualTo(200);
    assertThat(json(detail).path("confirmationCode").isNull()).isTrue();
    assertThat(
            owner.post(ORDERS + "/" + id + "/cancel", "{\"expectedVersion\":0}", null).statusCode())
        .isEqualTo(200);
    assertThat(owner.post(ORDERS, BODY, "wrong-role").statusCode()).isEqualTo(403);
    assertThat(owner.get("/api/v1/stores/me").statusCode()).isEqualTo(403);
  }

  @Test
  void exposesOnlySimulatedNullContextOrdersAndPreventsStoreCancellationOfThem() throws Exception {
    Browser owner = new Browser();
    owner.login("STORE");
    JsonNode own = json(owner.post(ORDERS, BODY, "visibility"));
    UUID simulated = insertLegacyOrder(UUID.fromString("20000000-0000-4000-8000-000000000001"));
    UUID hidden = insertLegacyOrder(UUID.fromString(own.path("store").path("id").asString()));
    Browser visitor = new Browser();
    visitor.login("STORE");
    assertThat(visitor.get(ORDERS + "/" + simulated).statusCode()).isEqualTo(200);
    assertThat(visitor.get(ORDERS + "/" + hidden).statusCode()).isEqualTo(404);
    assertThat(
            visitor
                .post(ORDERS + "/" + simulated + "/cancel", "{\"expectedVersion\":0}", null)
                .statusCode())
        .isEqualTo(404);
    String list = json(visitor.get(ORDERS)).path("items").toString();
    assertThat(list).contains(simulated.toString()).doesNotContain(hidden.toString());
    assertThat(list).doesNotContain("confirmationCode");
    visitor.login("OPERATIONS");
    assertThat(
            visitor
                .post(ORDERS + "/" + simulated + "/cancel", "{\"expectedVersion\":0}", null)
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  @Timeout(40)
  void serializesSameKeyAndEnforcesFiveActiveOrdersAcrossConcurrentRequests() throws Exception {
    Browser browser = new Browser();
    browser.login("STORE");
    List<HttpResponse<String>> same = parallel(6, () -> browser.post(ORDERS, BODY, "same-key"));
    assertThat(same).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
    assertThat(same.stream().map(response -> json(response).path("id").asString()).distinct())
        .hasSize(1);
    UUID first = UUID.fromString(json(same.getFirst()).path("id").asString());
    assertThat(countEvents(first)).isEqualTo(1);
    List<HttpResponse<String>> unique =
        parallel(6, () -> browser.post(ORDERS, BODY, "unique-" + UUID.randomUUID()));
    assertThat(unique.stream().filter(response -> response.statusCode() == 201)).hasSize(4);
    assertThat(unique.stream().filter(response -> response.statusCode() == 429)).hasSize(2);
    assertThat(
            jdbc.sql("SELECT count(*) FROM orders WHERE visitor_context_id=:context")
                .param("context", contextOf(browser.userId))
                .query(Long.class)
                .single())
        .isEqualTo(5);
    assertThat(browser.post(ORDERS, BODY, "same-key").statusCode()).isEqualTo(201);
  }

  @Test
  @Timeout(30)
  void acceptsOnlyOneConcurrentCancellationAtTheExpectedVersion() throws Exception {
    Browser browser = new Browser();
    browser.login("STORE");
    UUID id =
        UUID.fromString(json(browser.post(ORDERS, BODY, "cancel-race")).path("id").asString());
    var responses =
        parallel(
            2, () -> browser.post(ORDERS + "/" + id + "/cancel", "{\"expectedVersion\":0}", null));
    assertThat(responses.stream().map(HttpResponse::statusCode).sorted().toList())
        .containsExactly(200, 409);
    assertThat(countEvents(id)).isEqualTo(2);
    assertThat(
            jdbc.sql("SELECT version FROM orders WHERE id=:id")
                .param("id", id)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void keepsTimelineAtTheCapturedVersionWhenATransitionCommitsBetweenReads() throws Exception {
    Browser browser = new Browser();
    browser.login("STORE");
    UUID id =
        UUID.fromString(json(browser.post(ORDERS, BODY, "timeline-race")).path("id").asString());
    AtomicBoolean transitioned = new AtomicBoolean();
    doAnswer(
            call -> {
              String sql = call.getArgument(0);
              if (sql.contains("FROM order_events") && transitioned.compareAndSet(false, true)) {
                lifecycle.cancelByStore(id, 0, "test:transition-between-reads");
              }
              return call.callRealMethod();
            })
        .when(jdbc)
        .sql(anyString());
    JsonNode detail = json(browser.get(ORDERS + "/" + id));
    assertThat(transitioned).isTrue();
    assertThat(detail.path("version").asLong()).isZero();
    assertThat(detail.path("status").asString()).isEqualTo("SEARCHING");
    assertThat(detail.path("timeline")).hasSize(1);
    assertThat(countEvents(id)).isEqualTo(2);
    assertThat(
            jdbc.sql("SELECT version FROM orders WHERE id=:id")
                .param("id", id)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void rejectsMalformedAndOutsideRequestsWithoutReservingTheirIdempotencyKey() throws Exception {
    Browser browser = new Browser();
    browser.login("STORE");
    assertThat(browser.post(ORDERS, "{\"dropoff\":{\"latitude\":-23.55}}", "invalid").statusCode())
        .isEqualTo(400);
    assertThat(browser.post(ORDERS, "{\"dropoff\":null}", "invalid").statusCode()).isEqualTo(400);
    assertThat(browser.post(ORDERS, BODY, "invalid key").statusCode()).isEqualTo(400);
    assertThat(browser.post(ORDERS, BODY, null).statusCode()).isEqualTo(400);
    assertThat(
            browser
                .post(ORDERS, "{\"dropoff\":{\"latitude\":-22,\"longitude\":-46}}", "invalid")
                .statusCode())
        .isEqualTo(422);
    assertThat(browser.get(ORDERS + "?page=-1").statusCode()).isEqualTo(400);
    assertThat(browser.get(ORDERS + "?size=101").statusCode()).isEqualTo(400);
    assertThat(browser.post(ORDERS, BODY, "invalid").statusCode()).isEqualTo(201);
    assertThat(browser.get("/api/v1/stores/me").statusCode()).isEqualTo(200);
    assertThat(new Browser().get(ORDERS).statusCode()).isEqualTo(401);
    browser.login("COURIER");
    assertThat(browser.get(ORDERS).statusCode()).isEqualTo(403);
    assertThat(browser.post(ORDERS, BODY, "courier").statusCode()).isEqualTo(403);
  }

  @Test
  void mapsRoutingAndRateFailuresAndLeavesNoOrderBehind() throws Exception {
    Browser browser = new Browser();
    browser.login("STORE");
    doThrow(new RoutingException(RoutingException.Reason.NO_ROUTE, "Sem rota."))
        .when(routing)
        .route(any(), any());
    assertThat(browser.post(ORDERS, BODY, "retry-route").statusCode()).isEqualTo(422);
    doThrow(new RoutingException(RoutingException.Reason.GRAPH_UNAVAILABLE, "Indisponível."))
        .when(routing)
        .route(any(), any());
    assertThat(browser.post(ORDERS, BODY, "retry-route").statusCode()).isEqualTo(503);
    availableRouting();
    UUID context = contextOf(browser.userId);
    doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "Aguarde."))
        .when(limits)
        .checkOrderCreation(context);
    assertThat(browser.post(ORDERS, BODY, "retry-rate").statusCode()).isEqualTo(429);
    assertThat(
            jdbc.sql("SELECT count(*) FROM orders WHERE visitor_context_id=:context")
                .param("context", contextOf(browser.userId))
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void includesTheAreaBoundaryAndRejectsARoadMatchOutsideTheArea() throws Exception {
    Browser browser = new Browser();
    browser.login("STORE");
    assertThat(
            browser
                .post(
                    ORDERS,
                    "{\"dropoff\":{\"latitude\":-23.625,\"longitude\":-46.730}}",
                    "boundary")
                .statusCode())
        .isEqualTo(201);
    doAnswer(
            call -> {
              GeoPoint origin = call.getArgument(0);
              GeoPoint destination = call.getArgument(1);
              GeoPoint outside = new GeoPoint(-23.7, -46.7);
              return new Route(
                  List.of(origin, outside),
                  1_000,
                  Duration.ofSeconds(120),
                  Duration.ofSeconds(40),
                  new SnappedPoint(origin, origin, 0),
                  new SnappedPoint(destination, outside, 100));
            })
        .when(routing)
        .route(any(), any());
    assertThat(browser.post(ORDERS, BODY, "outside-road-match").statusCode()).isEqualTo(422);
    assertThat(
            jdbc.sql("SELECT count(*) FROM orders WHERE visitor_context_id=:context")
                .param("context", contextOf(browser.userId))
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void rollsBackTheOrderAndKeyWhenTheAppendOnlyEventCannotBeInserted() throws Exception {
    Browser browser = new Browser();
    browser.login("STORE");
    UUID store =
        jdbc.sql("SELECT store_id FROM users WHERE id=:id")
            .param("id", browser.userId)
            .query(UUID.class)
            .single();
    jdbc.sql(
            """
        CREATE FUNCTION reject_test_order_creation() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.type='ORDER_CREATED' AND EXISTS (
            SELECT 1 FROM orders WHERE id=NEW.order_id AND store_id='%s'::uuid
          ) THEN RAISE EXCEPTION 'Evento recusado pelo teste'; END IF;
          RETURN NEW;
        END;
        $$
        """
                .formatted(store))
        .update();
    jdbc.sql(
            "CREATE TRIGGER test_order_event_failure BEFORE INSERT ON order_events "
                + "FOR EACH ROW EXECUTE FUNCTION reject_test_order_creation()")
        .update();
    try {
      assertThat(browser.post(ORDERS, BODY, "atomic").statusCode()).isEqualTo(500);
      assertThat(
              jdbc.sql("SELECT count(*) FROM orders WHERE visitor_context_id=:context")
                  .param("context", contextOf(browser.userId))
                  .query(Long.class)
                  .single())
          .isZero();
      assertThat(
              jdbc.sql("SELECT count(*) FROM order_idempotency WHERE context_id=:context")
                  .param("context", contextOf(browser.userId))
                  .query(Long.class)
                  .single())
          .isZero();
    } finally {
      jdbc.sql("DROP TRIGGER test_order_event_failure ON order_events").update();
      jdbc.sql("DROP FUNCTION reject_test_order_creation()").update();
    }
    assertThat(browser.post(ORDERS, BODY, "atomic").statusCode()).isEqualTo(201);
  }

  private UUID insertLegacyOrder(UUID store) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO orders (id,public_code,store_id,dropoff,status,sla_deadline,next_attempt_at)
        VALUES (:id,:code,:store,ST_GeogFromText('SRID=4326;POINT(-46.65 -23.559)'),
          'SEARCHING',CURRENT_TIMESTAMP+INTERVAL '30 minutes',CURRENT_TIMESTAMP)
        """)
        .param("id", id)
        .param("code", UUID.randomUUID().toString())
        .param("store", store)
        .update();
    return id;
  }

  private long countEvents(UUID id) {
    return jdbc.sql("SELECT count(*) FROM order_events WHERE order_id=:id")
        .param("id", id)
        .query(Long.class)
        .single();
  }

  private UUID contextOf(UUID user) {
    return jdbc.sql("SELECT context_id FROM users WHERE id=:id")
        .param("id", user)
        .query(UUID.class)
        .single();
  }

  private List<HttpResponse<String>> parallel(int count, Callable<HttpResponse<String>> action)
      throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(count);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          java.util.stream.IntStream.range(0, count)
              .mapToObj(
                  index ->
                      executor.submit(
                          () -> {
                            barrier.await(10, TimeUnit.SECONDS);
                            return action.call();
                          }))
              .toList();
      var results = new java.util.ArrayList<HttpResponse<String>>();
      for (var future : futures) {
        results.add(future.get(20, TimeUnit.SECONDS));
      }
      return results;
    }
  }

  private JsonNode json(HttpResponse<String> response) {
    return mapper.readTree(response.body());
  }

  private final class Browser {
    private final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private String csrfHeader;
    private String csrf;
    private UUID userId;

    void login(String role) throws Exception {
      refreshCsrf();
      var response = post("/api/v1/auth/demo", "{\"role\":\"" + role + "\"}", null);
      assertThat(response.statusCode()).isEqualTo(200);
      userId = UUID.fromString(json(response).path("user").path("id").asString());
      refreshCsrf();
    }

    void refreshCsrf() throws Exception {
      var token = json(get("/api/v1/auth/csrf"));
      csrfHeader = token.path("headerName").asString();
      csrf = token.path("token").asString();
    }

    HttpResponse<String> get(String path) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(20))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body, String key) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json");
      if (csrf != null) {
        request.header(csrfHeader, csrf);
      }
      if (key != null) {
        request.header("Idempotency-Key", key);
      }
      return client.send(
          request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RoutingTestConfiguration {
    @Bean
    @Primary
    RoutingService ordersRoutingMock() {
      return mock(RoutingService.class);
    }
  }
}
