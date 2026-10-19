package dev.bora.identity.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.bora.dispatch.DispatchActivation;
import dev.bora.dispatch.DispatchCoordinator;
import dev.bora.routing.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "bora.simulator.key=test-only-simulator-key-0123456789abcdef",
      "bora.simulator.seed=2026",
      "bora.routing.simulation-speed=3",
      "bora.dispatch.worker-enabled=false",
      "bora.dispatch.expiry-enabled=false",
      "bora.fulfillment.reconciliation-enabled=false",
      "server.servlet.session.cookie.secure=false"
    })
@Import(SimulatorApiIT.Dependencies.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SimulatorApiIT {
  private static final String KEY = "test-only-simulator-key-0123456789abcdef";
  private static final String ORDER = "{\"dropoff\":{\"latitude\":-23.559,\"longitude\":-46.650}}";

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @Autowired JdbcClient jdbc;
  @Autowired RoutingService routing;
  @Autowired DispatchCoordinator dispatch;
  @Autowired JsonMapper json;
  @MockitoBean DispatchActivation activation;
  @LocalServerPort int port;

  @BeforeEach
  void prepare() {
    reset(routing);
    when(activation.allowsCreation()).thenReturn(true);
    when(routing.snap(any()))
        .thenAnswer(
            call -> {
              GeoPoint point = call.getArgument(0);
              return new SnappedPoint(point, point, 0);
            });
    when(routing.route(any(), any()))
        .thenAnswer(
            call -> {
              GeoPoint origin = call.getArgument(0), destination = call.getArgument(1);
              return new Route(
                  List.of(origin, destination),
                  1_000,
                  Duration.ofSeconds(120),
                  Duration.ofSeconds(40),
                  new SnappedPoint(origin, origin, 0),
                  new SnappedPoint(destination, destination, 0));
            });
    jdbc.sql("DELETE FROM rate_limit_buckets").update();
    jdbc.sql(
            "UPDATE orders SET next_attempt_at=CURRENT_TIMESTAMP+interval '1 hour' WHERE status='SEARCHING'")
        .update();
    jdbc.sql(
            "UPDATE offers SET status='CANCELED',responded_at=GREATEST(created_at,CURRENT_TIMESTAMP) WHERE status='PENDING'")
        .update();
    jdbc.sql("UPDATE couriers SET status='OFFLINE' WHERE status IN ('AVAILABLE','OFFERED')")
        .update();
  }

  @Test
  void scenarioIsReadOnlyStableAndRequiresDedicatedSecret() throws Exception {
    try (Browser browser = new Browser()) {
      long before = identities();
      assertThat(browser.get("/simulator/scenario", null).statusCode()).isEqualTo(403);
      assertThat(browser.get("/simulator/scenario", "wrong").statusCode()).isEqualTo(403);
      var first = tree(browser.get("/simulator/scenario", KEY));
      var second = tree(browser.get("/simulator/scenario", KEY));
      assertThat(first.path("couriers")).hasSize(120);
      assertThat(first.path("stores")).hasSize(40);
      assertThat(first.path("destinations")).hasSize(40);
      assertThat(first.path("seed").asLong()).isEqualTo(2026);
      assertThat(first.path("speed").asDouble()).isEqualTo(3);
      assertThat(first.path("couriers")).isEqualTo(second.path("couriers"));
      assertThat(first.path("stores")).isEqualTo(second.path("stores"));
      assertThat(first.toString()).doesNotContain(KEY, "confirmation", "contextId", "userId");
      assertThat(identities()).isEqualTo(before);
    }
  }

  @Test
  void loginRequiresCsrfExactSecretAndValidBoundedRoleAndIndex() throws Exception {
    try (Browser browser = new Browser()) {
      String body = "{\"role\":\"COURIER\",\"index\":0}";
      assertThat(browser.post("/auth/simulator", body, KEY, null, false).statusCode())
          .isEqualTo(403);
      browser.csrf();
      assertThat(browser.post("/auth/simulator", body, null, null, true).statusCode())
          .isEqualTo(403);
      assertThat(browser.post("/auth/simulator", body, "wrong", null, true).statusCode())
          .isEqualTo(403);
      for (String invalid :
          List.of(
              "{\"role\":\"COURIER\",\"index\":120}",
              "{\"role\":\"STORE\",\"index\":40}",
              "{\"role\":\"STORE\",\"index\":-1}",
              "{\"role\":\"OPERATIONS\",\"index\":0}",
              "{\"role\":\"COURIER\"}")) {
        assertThat(browser.post("/auth/simulator", invalid, KEY, null, true).statusCode())
            .isEqualTo(400);
      }
      assertThat(browser.simulator("COURIER", 0).path("authenticated").asBoolean()).isTrue();
      assertThat(
              jdbc.sql("SELECT count(*) FROM rate_limit_buckets WHERE id LIKE 'demo:%'")
                  .query(Long.class)
                  .single())
          .isZero();
    }
  }

  @Test
  void actorReloginPreservesIdentityCourierLocationEpochAndActiveAssignment() throws Exception {
    try (Browser first = new Browser();
        Browser second = new Browser()) {
      JsonNode session = first.simulator("COURIER", 10);
      UUID user = UUID.fromString(session.path("user").path("id").asString());
      UUID courier = UUID.fromString(session.path("user").path("courierId").asString());
      UUID order = UUID.randomUUID();
      jdbc.sql(
              """
          UPDATE couriers SET status='BUSY',location=ST_SetSRID(ST_MakePoint(-46.655,-23.56),4326)::geography,
            location_updated_at=CURRENT_TIMESTAMP,version=7,position_epoch=12 WHERE id=:id
          """)
          .param("id", courier)
          .update();
      jdbc.sql(
              """
          INSERT INTO orders(id,public_code,store_id,dropoff,status,courier_id,sla_deadline,created_at,assigned_at)
          VALUES(:id,:code,:store,ST_SetSRID(ST_MakePoint(-46.65,-23.559),4326)::geography,
            'ASSIGNED',:courier,CURRENT_TIMESTAMP+interval '1 hour',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
          """)
          .param("id", order)
          .param("code", order.toString().replace("-", ""))
          .param("store", SimulatorDirectory.storeId(10))
          .param("courier", courier)
          .update();
      String before = courierSnapshot(courier);
      JsonNode relogin = second.simulator("COURIER", 10);
      assertThat(relogin.path("user").path("id").asString()).isEqualTo(user.toString());
      assertThat(relogin.path("user").path("courierId").asString()).isEqualTo(courier.toString());
      assertThat(courierSnapshot(courier)).isEqualTo(before);
      assertThat(
              jdbc.sql("SELECT visitor FROM users WHERE id=:id")
                  .param("id", user)
                  .query(Boolean.class)
                  .single())
          .isFalse();
      assertThat(tree(second.get("/courier/work", null)).path("order").path("id").asString())
          .isEqualTo(order.toString());
    }
  }

  @Test
  void concurrentLoginOfSameActorCreatesExactlyOneContextUserAndCourier() throws Exception {
    try (Browser first = new Browser();
        Browser second = new Browser();
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var barrier = new java.util.concurrent.CyclicBarrier(2);
      var firstLogin =
          executor.submit(
              () -> {
                barrier.await();
                return first.simulator("COURIER", 30);
              });
      var secondLogin =
          executor.submit(
              () -> {
                barrier.await();
                return second.simulator("COURIER", 30);
              });
      JsonNode one = firstLogin.get(15, java.util.concurrent.TimeUnit.SECONDS);
      JsonNode two = secondLogin.get(15, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(one.path("user").path("id")).isEqualTo(two.path("user").path("id"));
      UUID courier = SimulatorDirectory.courierId(30);
      assertThat(
              jdbc.sql("SELECT count(*) FROM couriers WHERE id=:id")
                  .param("id", courier)
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
      assertThat(
              jdbc.sql("SELECT count(*) FROM users WHERE courier_id=:id AND NOT visitor")
                  .param("id", courier)
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
    }
  }

  @Test
  void expiredActorSessionCanRenewSameIdentityWithoutVisitorLoginAndWithoutReset()
      throws Exception {
    try (Browser browser = new Browser()) {
      JsonNode original = browser.simulator("COURIER", 11);
      UUID user = UUID.fromString(original.path("user").path("id").asString());
      jdbc.sql(
              "UPDATE demo_contexts SET expires_at=created_at WHERE id=(SELECT context_id FROM users WHERE id=:id)")
          .param("id", user)
          .update();
      assertThat(tree(browser.get("/auth/me", null)).path("authenticated").asBoolean()).isFalse();
      assertThat(browser.simulator("COURIER", 11).path("user").path("id").asString())
          .isEqualTo(user.toString());
    }
  }

  @Test
  void publicDemoEntryCannotReuseTheInternalActorsContext() throws Exception {
    try (Browser browser = new Browser()) {
      UUID actor =
          UUID.fromString(browser.simulator("COURIER", 12).path("user").path("id").asString());
      UUID actorContext = context(actor);
      JsonNode visitor = browser.visitor("COURIER");
      UUID visitorId = UUID.fromString(visitor.path("user").path("id").asString());
      assertThat(visitorId).isNotEqualTo(actor);
      assertThat(context(visitorId)).isNotEqualTo(actorContext);
      assertThat(
              jdbc.sql("SELECT visitor FROM users WHERE id=:id")
                  .param("id", visitorId)
                  .query(Boolean.class)
                  .single())
          .isTrue();
    }
  }

  @Test
  void simulatedStoreCreatesPublicOrdersWithoutConfirmationAndReplaysThemSafely() throws Exception {
    try (Browser store = new Browser();
        Browser visitor = new Browser()) {
      store.simulator("STORE", 0);
      var original = store.create("public-sim-order");
      assertThat(original.statusCode()).isEqualTo(201);
      var body = tree(original);
      UUID id = UUID.fromString(body.path("id").asString());
      assertThat(body.path("confirmationCode").isNull()).isTrue();
      assertThat(
              jdbc.sql(
                      "SELECT count(*) FROM orders WHERE id=:id AND visitor_context_id IS NULL AND owner_user_id IS NULL AND confirmation_code_hash IS NULL")
                  .param("id", id)
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
      assertThat(tree(store.create("public-sim-order"))).isEqualTo(body);
      visitor.visitor("STORE");
      assertThat(visitor.get("/orders/" + id, null).statusCode()).isEqualTo(200);
      assertThat(tree(visitor.get("/orders/" + id, null)).path("confirmationCode").isNull())
          .isTrue();
      assertThat(
              store
                  .post("/orders/" + id + "/cancel", "{\"expectedVersion\":0}", null, null, true)
                  .statusCode())
          .isEqualTo(200);
    }
  }

  @Test
  void internalStoreCannotReadCancelPrivateOrCancelAnotherPublicStoresOrder() throws Exception {
    try (Browser privateOwner = new Browser();
        Browser first = new Browser();
        Browser second = new Browser()) {
      privateOwner.visitor("STORE");
      String privateId = tree(privateOwner.create("private-order")).path("id").asString();
      first.simulator("STORE", 1);
      second.simulator("STORE", 2);
      String publicId = tree(second.create("other-public-order")).path("id").asString();
      assertThat(first.get("/orders/" + privateId, null).statusCode()).isEqualTo(404);
      assertThat(
              first
                  .post(
                      "/orders/" + privateId + "/cancel",
                      "{\"expectedVersion\":0}",
                      null,
                      null,
                      true)
                  .statusCode())
          .isEqualTo(404);
      assertThat(first.get("/orders/" + publicId, null).statusCode()).isEqualTo(200);
      assertThat(
              first
                  .post(
                      "/orders/" + publicId + "/cancel",
                      "{\"expectedVersion\":0}",
                      null,
                      null,
                      true)
                  .statusCode())
          .isEqualTo(404);
    }
  }

  @Test
  void simulatedStoreSkipsVisitorFiveActiveCapButKeepsRealOrderRateLimit() throws Exception {
    try (Browser store = new Browser()) {
      store.simulator("STORE", 3);
      for (int index = 0; index < 10; index++) {
        assertThat(store.create("rate-" + index).statusCode()).isEqualTo(201);
      }
      assertThat(store.create("rate-eleven").statusCode()).isEqualTo(429);
    }
  }

  @Test
  void publicOrderCanBeOfferedToVisitorCourierAndAcceptedThroughSameApi() throws Exception {
    try (Browser store = new Browser();
        Browser courier = new Browser()) {
      store.simulator("STORE", 4);
      String order = tree(store.create("visitor-public-order")).path("id").asString();
      courier.visitor("COURIER");
      courier.online();
      assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
      JsonNode offer = tree(courier.get("/courier/offer", null)).path("offer");
      assertThat(offer.path("orderId").asString()).isEqualTo(order);
      assertThat(
              courier
                  .post(
                      "/offers/" + offer.path("id").asString() + "/accept", null, null, null, true)
                  .statusCode())
          .isEqualTo(204);
      assertThat(
              tree(courier.get("/courier/work", null))
                  .path("order")
                  .path("confirmationRequired")
                  .asBoolean())
          .isFalse();
    }
  }

  @Test
  void simulatedCourierReceivesAndAcceptsPublicOfferThroughNormalSession() throws Exception {
    try (Browser store = new Browser();
        Browser courier = new Browser()) {
      store.simulator("STORE", 6);
      String order = tree(store.create("sim-public-order")).path("id").asString();
      courier.simulator("COURIER", 22);
      courier.online(-23.5504, -46.6333);
      assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
      JsonNode offer = tree(courier.get("/courier/offer", null)).path("offer");
      assertThat(offer.path("orderId").asString()).isEqualTo(order);
      assertThat(
              courier
                  .post(
                      "/offers/" + offer.path("id").asString() + "/accept", null, null, null, true)
                  .statusCode())
          .isEqualTo(204);
      assertThat(
              tree(courier.get("/courier/work", null))
                  .path("order")
                  .path("confirmationRequired")
                  .asBoolean())
          .isFalse();
    }
  }

  @Test
  void privateOrderNeverGoesToSimulatedCourierEvenWhenCloserAndFaster() throws Exception {
    try (Browser owner = new Browser();
        Browser simulated = new Browser()) {
      owner.visitor("STORE");
      String order = tree(owner.create("private-dispatch")).path("id").asString();
      owner.visitor("COURIER");
      owner.online(-23.5624, -46.6559);
      simulated.simulator("COURIER", 20);
      simulated.online();
      doAnswer(
              call -> {
                GeoPoint origin = call.getArgument(0), destination = call.getArgument(1);
                long seconds = origin.latitude() < -23.562 ? 120 : 1;
                return new Route(
                    List.of(origin, destination),
                    1_000,
                    Duration.ofSeconds(seconds * 3),
                    Duration.ofSeconds(seconds),
                    new SnappedPoint(origin, origin, 0),
                    new SnappedPoint(destination, destination, 0));
              })
          .when(routing)
          .route(any(), any());
      UUID simulatedId = SimulatorDirectory.courierId(20);
      assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
      JsonNode ownOffer = tree(owner.get("/courier/offer", null)).path("offer");
      assertThat(ownOffer.path("orderId").asString()).isEqualTo(order);
      assertThat(tree(simulated.get("/courier/offer", null)).path("offer").isNull()).isTrue();
      assertThat(
              jdbc.sql("SELECT count(*) FROM offers WHERE order_id=:id AND courier_id=:courier")
                  .param("id", UUID.fromString(order))
                  .param("courier", simulatedId)
                  .query(Long.class)
                  .single())
          .isZero();
    }
  }

  @Test
  void idleRouteRequiresSecretCsrfAndInternalCourierAndUsesSharedSimulationSpeed()
      throws Exception {
    String route =
        "{\"origin\":{\"latitude\":-23.56,\"longitude\":-46.655},\"destination\":{\"latitude\":-23.559,\"longitude\":-46.65}}";
    try (Browser visitor = new Browser();
        Browser courier = new Browser();
        Browser store = new Browser()) {
      visitor.visitor("COURIER");
      assertThat(visitor.post("/simulator/route", route, KEY, null, true).statusCode())
          .isEqualTo(403);
      store.simulator("STORE", 5);
      assertThat(store.post("/simulator/route", route, KEY, null, true).statusCode())
          .isEqualTo(403);
      courier.simulator("COURIER", 21);
      assertThat(courier.post("/simulator/route", route, null, null, true).statusCode())
          .isEqualTo(403);
      assertThat(courier.post("/simulator/route", route, KEY, null, false).statusCode())
          .isEqualTo(403);
      JsonNode body = tree(courier.post("/simulator/route", route, KEY, null, true));
      assertThat(body.path("geometry")).hasSize(2);
      assertThat(body.path("durationMillis").asLong()).isEqualTo(120_000);
      assertThat(body.path("simulatedDurationMillis").asLong()).isEqualTo(40_000);
    }
  }

  private long identities() {
    return jdbc.sql(
            "SELECT (SELECT count(*) FROM users)+(SELECT count(*) FROM demo_contexts)+(SELECT count(*) FROM couriers)")
        .query(Long.class)
        .single();
  }

  private UUID context(UUID user) {
    return jdbc.sql("SELECT context_id FROM users WHERE id=:id")
        .param("id", user)
        .query(UUID.class)
        .single();
  }

  private String courierSnapshot(UUID courier) {
    return jdbc.sql(
            "SELECT status||':'||version||':'||position_epoch||':'||location_updated_at||':'||ST_AsText(location::geometry) FROM couriers WHERE id=:id")
        .param("id", courier)
        .query(String.class)
        .single();
  }

  private JsonNode tree(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  private final class Browser implements AutoCloseable {
    private final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    private String token;

    void csrf() throws Exception {
      token = tree(get("/auth/csrf", null)).path("token").asString();
    }

    JsonNode simulator(String role, int index) throws Exception {
      csrf();
      var response =
          post(
              "/auth/simulator",
              "{\"role\":\"" + role + "\",\"index\":" + index + "}",
              KEY,
              null,
              true);
      assertThat(response.statusCode()).isEqualTo(200);
      csrf();
      return tree(response);
    }

    JsonNode visitor(String role) throws Exception {
      csrf();
      var response = post("/auth/demo", "{\"role\":\"" + role + "\"}", null, null, true);
      assertThat(response.statusCode()).isEqualTo(200);
      csrf();
      return tree(response);
    }

    void online() throws Exception {
      online(-23.5614, -46.6559);
    }

    void online(double latitude, double longitude) throws Exception {
      assertThat(
              post(
                      "/courier/availability",
                      "{\"online\":true,\"position\":{\"latitude\":"
                          + latitude
                          + ",\"longitude\":"
                          + longitude
                          + "}}",
                      null,
                      null,
                      true)
                  .statusCode())
          .isEqualTo(200);
    }

    HttpResponse<String> create(String idempotency) throws Exception {
      return post("/orders", ORDER, null, idempotency, true);
    }

    HttpResponse<String> get(String path, String key) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
              .timeout(Duration.ofSeconds(15))
              .GET();
      if (key != null) request.header(SimulatorGuard.HEADER, key);
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(
        String path, String body, String key, String idempotency, boolean csrf) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
              .timeout(Duration.ofSeconds(15));
      if (key != null) request.header(SimulatorGuard.HEADER, key);
      if (idempotency != null) request.header("Idempotency-Key", idempotency);
      if (csrf && token != null) request.header("X-CSRF-TOKEN", token);
      if (body != null) request.header("Content-Type", "application/json");
      request.POST(
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
      client.close();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    @Primary
    RoutingService simulatorRoutingMock() {
      return mock(RoutingService.class);
    }
  }
}
