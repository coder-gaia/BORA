package dev.bora.operations.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.bora.demo.DemoLimits;
import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.routing.*;
import dev.bora.shared.ApiException;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "bora.dispatch.worker-enabled=false",
      "bora.dispatch.expiry-enabled=false",
      "bora.fulfillment.reconciliation-enabled=false",
      "bora.couriers.flush-initial-delay-ms=3600000"
    })
@Import(OperationsMetricsApiIT.Dependencies.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperationsMetricsApiIT {
  static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  static final String METRICS = "/api/v1/ops/metrics";
  static final UUID SEED_STORE = UUID.fromString("20000000-0000-4000-8000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @Autowired MutableClock clock;
  @Autowired JsonMapper mapper;
  @Autowired OperationsMetricsService metrics;
  @MockitoSpyBean JdbcClient jdbc;
  @MockitoBean DemoLimits limits;
  @LocalServerPort int port;

  @BeforeEach
  void prepare() {
    clock.at.set(NOW);
    // Stop publishing public fixtures from prior methods.
    // Existing event history is retained instead of deleting append-only records.
    jdbc.sql("UPDATE stores SET simulated=false WHERE name LIKE 'M12 metrics fixture %'").update();
    clearInvocations(jdbc);
  }

  @Test
  void aggregatesAllSixIndicatorsFromOwnAndPublicOrdersWithoutOtherVisitors() throws Exception {
    Actor owner = actor();
    order(owner, false, "SEARCHING", NOW.minusSeconds(240), NOW.plusSeconds(600), null);
    order(owner, false, "ASSIGNED", NOW.minusSeconds(360), NOW.minusSeconds(1), null);
    order(owner, false, "AT_STORE", NOW.minusSeconds(360), NOW.plusSeconds(300), null);
    order(owner, false, "PICKED_UP", NOW.minusSeconds(360), NOW.plusSeconds(301), null);
    order(
        owner,
        false,
        "DELIVERED",
        NOW.minusSeconds(7200),
        NOW.minusSeconds(1),
        NOW.minusSeconds(600));
    order(
        owner,
        false,
        "DELIVERED",
        NOW.minusSeconds(7200),
        NOW.minusSeconds(1),
        NOW.minusSeconds(3601));
    order(owner, false, "CANCELED", NOW.minusSeconds(7200), NOW.minusSeconds(1), null);
    order(owner, true, "SEARCHING", NOW.minusSeconds(60), NOW.plusSeconds(1), null);
    order(
        owner,
        true,
        "DELIVERED",
        NOW.minusSeconds(7200),
        NOW.minusSeconds(1),
        NOW.minusSeconds(60));
    Actor other = actor();
    order(other, false, "SEARCHING", NOW.minusSeconds(600), NOW.minusSeconds(1), null);
    order(other, false, "DELIVERED", NOW.minusSeconds(7200), NOW.minusSeconds(1), NOW);
    JsonNode result = read(owner);
    assertCounts(result, 5, 2, 1, 1, 2, 2);
    assertThat(Instant.parse(result.path("serverTime").asString())).isEqualTo(NOW);
    assertThat(result.size()).isEqualTo(7);
    assertThat(result.toString())
        .doesNotContain(
            "confirmationCode",
            "publicCode",
            "courierId",
            owner.ownerId().toString(),
            other.ownerId().toString());
  }

  @Test
  void anEmptyAuthorizedViewReturnsZeros() throws Exception {
    assertCounts(read(actor()), 0, 0, 0, 0, 0, 0);
  }

  @Test
  void slaEdgesUseOneInstantAndClosedOrdersNeverGenerateAlerts() throws Exception {
    Actor owner = actor();
    order(owner, false, "SEARCHING", NOW.minusSeconds(600), NOW, null);
    order(owner, false, "ASSIGNED", NOW.minusSeconds(600), NOW.plus(1, ChronoUnit.MICROS), null);
    order(owner, false, "AT_STORE", NOW.minusSeconds(600), NOW.plusSeconds(300), null);
    order(
        owner,
        false,
        "PICKED_UP",
        NOW.minusSeconds(600),
        NOW.plusSeconds(300).plus(1, ChronoUnit.MICROS),
        null);
    order(owner, false, "DELIVERED", NOW.minusSeconds(7200), NOW.minusSeconds(1), NOW);
    order(owner, false, "CANCELED", NOW.minusSeconds(7200), NOW.minusSeconds(1), null);
    assertCounts(read(owner), 4, 1, 1, 1, 2, 1);
  }

  @Test
  void waitingUsesTheCurrentSearchAttemptAndInclusiveThreeMinuteBoundary() throws Exception {
    Actor owner = actor();
    UUID reassigned =
        order(owner, false, "SEARCHING", NOW.minusSeconds(600), NOW.plusSeconds(600), null);
    reassignEvent(reassigned, NOW.minusSeconds(30), 2);
    order(owner, false, "SEARCHING", NOW.minusSeconds(180), NOW.plusSeconds(600), null);
    order(
        owner,
        false,
        "SEARCHING",
        NOW.minusSeconds(180).plus(1, ChronoUnit.MICROS),
        NOW.plusSeconds(600),
        null);
    assertCounts(read(owner), 3, 3, 1, 0, 0, 0);
  }

  @Test
  void deliveryWindowIncludesExactlySixtyMinutesAndNowButExcludesOlderAndFuture() throws Exception {
    Actor owner = actor();
    order(
        owner,
        false,
        "DELIVERED",
        NOW.minusSeconds(7200),
        NOW.plusSeconds(100),
        NOW.minusSeconds(3600));
    order(
        owner,
        false,
        "DELIVERED",
        NOW.minusSeconds(7200),
        NOW.plusSeconds(100),
        NOW.minusSeconds(3600).minus(1, ChronoUnit.MICROS));
    order(owner, false, "DELIVERED", NOW.minusSeconds(7200), NOW.plusSeconds(100), NOW);
    order(
        owner,
        false,
        "DELIVERED",
        NOW.minusSeconds(7200),
        NOW.plusSeconds(100),
        NOW.plus(1, ChronoUnit.MICROS));
    assertCounts(read(owner), 0, 0, 0, 0, 0, 2);
  }

  @Test
  void roleAndContextRevocationAreCheckedWithoutPublishingPrivateCounts() throws Exception {
    Browser browser = new Browser();
    assertThat(browser.get(METRICS).statusCode()).isEqualTo(401);
    browser.login("STORE");
    assertThat(browser.get(METRICS).statusCode()).isEqualTo(403);
    browser.login("COURIER");
    assertThat(browser.get(METRICS).statusCode()).isEqualTo(403);
    Actor owner = actor();
    order(owner, false, "SEARCHING", NOW.minusSeconds(600), NOW.plusSeconds(100), null);
    DemoPrincipal principal =
        new DemoPrincipal(
            owner.browser().userId, owner.contextId(), DemoRole.OPERATIONS, null, null, true);
    jdbc.sql("UPDATE demo_contexts SET expires_at=created_at WHERE id=:id")
        .param("id", owner.contextId())
        .update();
    assertThatThrownBy(() -> metrics.read(principal))
        .isInstanceOfSatisfying(
            ApiException.class,
            failure -> assertThat(failure.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    assertThat(owner.browser().get(METRICS).statusCode()).isEqualTo(401);
  }

  @Test
  void theSixCountersAreComputedByOneSqlStatementRatherThanPageOrViewport() throws Exception {
    Actor owner = actor();
    for (int i = 0; i < 23; i++)
      order(owner, false, "SEARCHING", NOW.minusSeconds(600 + i), NOW.plusSeconds(600), null);
    clearInvocations(jdbc);
    assertCounts(read(owner), 23, 23, 23, 0, 0, 0);
    verify(jdbc, times(1)).sql(startsWith("WITH moment AS MATERIALIZED"));
  }

  @Test
  @Timeout(30)
  void concurrentStateChangesCannotMixActiveAndDeliveredCountersAcrossQueries() throws Exception {
    Actor owner = actor();
    UUID id = order(owner, false, "SEARCHING", NOW.minusSeconds(7200), NOW.plusSeconds(600), null);
    UUID courier = courier(false);
    AtomicBoolean keepWriting = new AtomicBoolean(true);
    AtomicInteger committedWrites = new AtomicInteger();
    CountDownLatch started = new CountDownLatch(1);
    Set<String> observed = new HashSet<>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var writer =
          executor.submit(
              () -> {
                int iteration = 0;
                while (keepWriting.get()) {
                  boolean delivered = iteration++ % 2 == 0;
                  jdbc.sql(
                          """
              UPDATE orders SET status=:status,courier_id=:courier,next_attempt_at=:next,
                assigned_at=:assigned,at_store_at=:atstore,picked_up_at=:picked,delivered_at=:delivered
              WHERE id=:id
              """)
                      .param("id", id)
                      .param("status", delivered ? "DELIVERED" : "SEARCHING")
                      .param("courier", delivered ? courier : null)
                      .param("next", delivered ? null : time(NOW.minusSeconds(7200)))
                      .param("assigned", delivered ? time(NOW.minusSeconds(7100)) : null)
                      .param("atstore", delivered ? time(NOW.minusSeconds(7000)) : null)
                      .param("picked", delivered ? time(NOW.minusSeconds(6900)) : null)
                      .param("delivered", delivered ? time(NOW) : null)
                      .update();
                  int writes = committedWrites.incrementAndGet();
                  if (writes >= 2) started.countDown();
                  Thread.sleep(2 + writes % 3);
                }
                return null;
              });
      try {
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        int writesAtReadStart = committedWrites.get();
        int reads = 0;
        do {
          JsonNode snapshot = read(owner);
          long active = snapshot.path("activeOrders").asLong();
          long delivered = snapshot.path("deliveredLastHour").asLong();
          assertThat(active + delivered).isEqualTo(1);
          assertThat(snapshot.path("searchingOrders").asLong()).isEqualTo(active);
          observed.add(active == 1 ? "SEARCHING" : "DELIVERED");
          reads++;
        } while (reads < 12 || (observed.size() < 2 && reads < 60));
        assertThat(observed).containsExactlyInAnyOrder("SEARCHING", "DELIVERED");
        assertThat(committedWrites.get()).isGreaterThan(writesAtReadStart);
        assertThat(writer.isDone()).as("writer overlaps the complete observation window").isFalse();
      } finally {
        keepWriting.set(false);
      }
      writer.get(15, TimeUnit.SECONDS);
    }
  }

  @Test
  void latestSearchAttemptUsesEventVersionWhenTheServerClockMovesBackwards() throws Exception {
    Actor owner = actor();
    UUID restarted =
        order(owner, false, "SEARCHING", NOW.minusSeconds(600), NOW.plusSeconds(600), null);
    reassignEvent(restarted, NOW.minusSeconds(120), 2);
    reassignEvent(restarted, NOW.minusSeconds(240), 4);
    UUID other =
        order(owner, false, "SEARCHING", NOW.minusSeconds(180), NOW.plusSeconds(600), null);
    assertCounts(read(owner), 2, 2, 2, 0, 0, 0);
    JsonNode queue = orders(owner, "?status=SEARCHING&sort=WAITING&scope=OWN");
    assertThat(queue.path("items").get(0).path("id").asString()).isEqualTo(restarted.toString());
    assertThat(queue.path("items").get(1).path("id").asString()).isEqualTo(other.toString());
    assertThat(Instant.parse(queue.path("items").get(0).path("searchingSince").asString()))
        .isEqualTo(NOW.minusSeconds(240));
  }

  @Test
  void waitingQueueOrdersByTheCurrentAttemptInsteadOfOriginalCreation() throws Exception {
    Actor owner = actor();
    UUID restarted =
        order(owner, false, "SEARCHING", NOW.minusSeconds(600), NOW.plusSeconds(600), null);
    reassignEvent(restarted, NOW.minusSeconds(10), 2);
    UUID oldestAttempt =
        order(owner, false, "SEARCHING", NOW.minusSeconds(400), NOW.plusSeconds(600), null);
    UUID middle =
        order(owner, false, "SEARCHING", NOW.minusSeconds(200), NOW.plusSeconds(600), null);
    UUID delivered =
        order(owner, false, "DELIVERED", NOW.minusSeconds(7200), NOW.plusSeconds(600), NOW);
    JsonNode page = orders(owner, "?status=SEARCHING&sort=WAITING&scope=OWN");
    assertThat(page.path("totalElements").asLong()).isEqualTo(3);
    assertThat(page.path("items").get(0).path("id").asString()).isEqualTo(oldestAttempt.toString());
    assertThat(page.path("items").get(1).path("id").asString()).isEqualTo(middle.toString());
    assertThat(page.path("items").get(2).path("id").asString()).isEqualTo(restarted.toString());
    assertThat(Instant.parse(page.path("items").get(2).path("searchingSince").asString()))
        .isEqualTo(NOW.minusSeconds(10));
    JsonNode finished = orders(owner, "?scope=OWN&status=DELIVERED");
    assertThat(finished.path("items").get(0).path("id").asString()).isEqualTo(delivered.toString());
    assertThat(finished.path("items").get(0).path("searchingSince").isNull()).isTrue();
  }

  @Test
  void queueScopeAndExactCodeSearchUseTheWholeAuthorizedSet() throws Exception {
    Actor owner = actor(), other = actor();
    UUID own = order(owner, false, "SEARCHING", NOW.minusSeconds(400), NOW.plusSeconds(600), null);
    UUID publicId =
        order(owner, true, "SEARCHING", NOW.minusSeconds(600), NOW.plusSeconds(600), null);
    UUID hidden =
        order(other, false, "SEARCHING", NOW.minusSeconds(900), NOW.plusSeconds(600), null);
    String ownCode = code(own), hiddenCode = code(hidden);
    JsonNode ownPage = orders(owner, "?status=SEARCHING&sort=WAITING&scope=OWN&code=" + ownCode);
    assertThat(ownPage.path("totalElements").asLong()).isEqualTo(1);
    assertThat(ownPage.path("items").get(0).path("id").asString()).isEqualTo(own.toString());
    assertThat(
            orders(owner, "?status=SEARCHING&sort=WAITING&code=" + hiddenCode)
                .path("totalElements")
                .asLong())
        .isZero();
    JsonNode publicPage = orders(owner, "?status=SEARCHING&sort=WAITING&scope=PUBLIC");
    assertThat(publicPage.path("totalElements").asLong()).isEqualTo(1);
    assertThat(publicPage.path("items").get(0).path("id").asString())
        .isEqualTo(publicId.toString());
    assertCounts(read(owner), 2, 2, 2, 0, 0, 0);
  }

  @Test
  void waitingPagesReuseTheCreationBoundaryAndReportCoherentPagination() throws Exception {
    Actor owner = actor();
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 4; i++)
      ids.add(
          order(
              owner,
              false,
              "SEARCHING",
              NOW.minusSeconds(400 - i * 10),
              NOW.plusSeconds(600),
              null));
    JsonNode first = orders(owner, "?status=SEARCHING&sort=WAITING&scope=OWN&size=2");
    assertThat(first.path("items").size()).isEqualTo(2);
    assertThat(first.path("totalElements").asLong()).isEqualTo(4);
    assertThat(first.path("totalPages").asInt()).isEqualTo(2);
    Instant boundary = Instant.parse(first.path("snapshotAt").asString());
    order(owner, false, "SEARCHING", boundary.plusSeconds(10), boundary.plusSeconds(600), null);
    String encoded =
        URLEncoder.encode(boundary.toString(), java.nio.charset.StandardCharsets.UTF_8);
    JsonNode second =
        orders(
            owner, "?status=SEARCHING&sort=WAITING&scope=OWN&size=2&page=1&snapshotAt=" + encoded);
    assertThat(second.path("totalElements").asLong()).isEqualTo(4);
    assertThat(second.path("items").get(0).path("id").asString()).isEqualTo(ids.get(2).toString());
    assertThat(second.path("items").get(1).path("id").asString()).isEqualTo(ids.get(3).toString());
  }

  @Test
  void waitingTiesHaveDeterministicIdOrdering() throws Exception {
    Actor owner = actor();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++)
      ids.add(
          order(owner, false, "SEARCHING", NOW.minusSeconds(60), NOW.plusSeconds(600), null)
              .toString());
    ids.sort(Comparator.naturalOrder());
    JsonNode result = orders(owner, "?status=SEARCHING&sort=WAITING&scope=OWN");
    for (int i = 0; i < ids.size(); i++)
      assertThat(result.path("items").get(i).path("id").asString()).isEqualTo(ids.get(i));
  }

  @Test
  void waitingSortRequiresOperationsSearchingStatusAndBoundedPageSize() throws Exception {
    Actor owner = actor();
    assertThat(owner.browser().get("/api/v1/orders?sort=WAITING").statusCode()).isEqualTo(400);
    assertThat(owner.browser().get("/api/v1/orders?sort=WAITING&status=DELIVERED").statusCode())
        .isEqualTo(400);
    assertThat(
            owner
                .browser()
                .get("/api/v1/orders?sort=WAITING&status=SEARCHING&size=101")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            owner.browser().get("/api/v1/orders?sort=WAITING&status=SEARCHING&size=0").statusCode())
        .isEqualTo(400);
    owner.browser().login("STORE");
    assertThat(owner.browser().get("/api/v1/orders?sort=WAITING&status=SEARCHING").statusCode())
        .isEqualTo(403);
    assertThat(owner.browser().get("/api/v1/orders?sort=RECENT").statusCode()).isEqualTo(200);
  }

  private JsonNode orders(Actor owner, String query) throws Exception {
    var response = owner.browser().get("/api/v1/orders" + query);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return json(response);
  }

  private String code(UUID order) {
    return jdbc.sql("SELECT public_code FROM orders WHERE id=:id")
        .param("id", order)
        .query(String.class)
        .single();
  }

  private Actor actor() throws Exception {
    Browser browser = new Browser();
    browser.login("STORE");
    UUID owner = browser.userId, context = browser.contextId;
    UUID store =
        jdbc.sql("SELECT store_id FROM users WHERE id=:id")
            .param("id", owner)
            .query(UUID.class)
            .single();
    browser.login("OPERATIONS");
    return new Actor(browser, context, owner, store);
  }

  private UUID publicStore() {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO stores(id,name,service_area_id,location,simulated)
        SELECT :id,:name,service_area_id,location,true FROM stores WHERE id=:seed
        """)
        .param("id", id)
        .param("name", "M12 metrics fixture " + id)
        .param("seed", SEED_STORE)
        .update();
    return id;
  }

  private UUID courier(boolean simulated) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO couriers(id,name,status,simulated,location,location_updated_at)
        SELECT :id,'M12 metrics courier','BUSY',:simulated,location,:now FROM stores WHERE id=:seed
        """)
        .param("id", id)
        .param("simulated", simulated)
        .param("now", time(NOW))
        .param("seed", SEED_STORE)
        .update();
    return id;
  }

  private UUID order(
      Actor actor,
      boolean publicOrder,
      String status,
      Instant created,
      Instant deadline,
      Instant delivered) {
    UUID id = UUID.randomUUID(), store = publicOrder ? publicStore() : actor.storeId();
    boolean carrying = Set.of("ASSIGNED", "AT_STORE", "PICKED_UP", "DELIVERED").contains(status);
    boolean atStore = Set.of("AT_STORE", "PICKED_UP", "DELIVERED").contains(status);
    boolean picked = Set.of("PICKED_UP", "DELIVERED").contains(status);
    jdbc.sql(
            """
        INSERT INTO orders(id,public_code,store_id,dropoff,status,courier_id,created_at,sla_deadline,
          next_attempt_at,assigned_at,at_store_at,picked_up_at,delivered_at,canceled_at,
          visitor_context_id,owner_user_id)
        SELECT :id,:code,id,location,:status,:courier,:created,:deadline,:next,:assigned,:atstore,
          :picked,:delivered,:canceled,:context,:owner FROM stores WHERE id=:store
        """)
        .param("id", id)
        .param("code", UUID.randomUUID().toString())
        .param("store", store)
        .param("status", status)
        .param("courier", carrying ? courier(publicOrder) : null)
        .param("created", time(created))
        .param("deadline", time(deadline))
        .param("next", status.equals("SEARCHING") ? time(created) : null)
        .param("assigned", carrying ? time(created.plusSeconds(1)) : null)
        .param("atstore", atStore ? time(created.plusSeconds(2)) : null)
        .param("picked", picked ? time(created.plusSeconds(3)) : null)
        .param("delivered", delivered == null ? null : time(delivered))
        .param("canceled", status.equals("CANCELED") ? time(NOW) : null)
        .param("context", publicOrder ? null : actor.contextId())
        .param("owner", publicOrder ? null : actor.ownerId())
        .update();
    return id;
  }

  private void reassignEvent(UUID id, Instant at, long version) {
    jdbc.sql("UPDATE orders SET version=:version WHERE id=:id")
        .param("id", id)
        .param("version", version)
        .update();
    jdbc.sql(
            """
        INSERT INTO order_events(order_id,type,payload,actor,occurred_at,order_version)
        VALUES(:id,'ORDER_REASSIGNED','{"from":"ASSIGNED","to":"SEARCHING"}'::jsonb,
          'metrics-test',:at,:version)
        """)
        .param("id", id)
        .param("at", time(at))
        .param("version", version)
        .update();
  }

  private JsonNode read(Actor actor) throws Exception {
    var response = actor.browser().get(METRICS);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return json(response);
  }

  private void assertCounts(
      JsonNode result,
      long active,
      long searching,
      long waiting,
      long overdue,
      long due,
      long delivered) {
    assertThat(result.path("activeOrders").asLong()).isEqualTo(active);
    assertThat(result.path("searchingOrders").asLong()).isEqualTo(searching);
    assertThat(result.path("searchingOverThreeMinutes").asLong()).isEqualTo(waiting);
    assertThat(result.path("overdueOrders").asLong()).isEqualTo(overdue);
    assertThat(result.path("dueWithinFiveMinutes").asLong()).isEqualTo(due);
    assertThat(result.path("deliveredLastHour").asLong()).isEqualTo(delivered);
  }

  private JsonNode json(HttpResponse<String> response) {
    return mapper.readTree(response.body());
  }

  private static OffsetDateTime time(Instant at) {
    return at.atOffset(ZoneOffset.UTC);
  }

  private record Actor(Browser browser, UUID contextId, UUID ownerId, UUID storeId) {}

  final class Browser {
    final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String csrfHeader, csrf;
    UUID userId, courierId, contextId;

    void login(String role) throws Exception {
      refresh();
      var response = post("/api/v1/auth/demo", "{\"role\":\"" + role + "\"}");
      assertThat(response.statusCode()).isEqualTo(200);
      JsonNode user = json(response).path("user");
      userId = UUID.fromString(user.path("id").asString());
      courierId =
          user.path("courierId").isNull()
              ? null
              : UUID.fromString(user.path("courierId").asString());
      contextId =
          jdbc.sql("SELECT context_id FROM users WHERE id=:id")
              .param("id", userId)
              .query(UUID.class)
              .single();
      refresh();
    }

    void refresh() throws Exception {
      JsonNode token = json(get("/api/v1/auth/csrf"));
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

    HttpResponse<String> post(String path, String body) throws Exception {
      return post(path, body, null);
    }

    HttpResponse<String> post(String path, String body, String key) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json");
      if (csrf != null) request.header(csrfHeader, csrf);
      if (key != null) request.header("Idempotency-Key", key);
      return client.send(
          request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }

    @Bean
    @Primary
    RoutingService mockRouting() {
      RoutingService routing = mock(RoutingService.class);
      when(routing.snap(any()))
          .thenAnswer(
              call -> {
                GeoPoint point = call.getArgument(0);
                return new SnappedPoint(point, point, 0);
              });
      return routing;
    }
  }

  static class MutableClock extends Clock {
    final AtomicReference<Instant> at = new AtomicReference<>(NOW);

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return at.get();
    }
  }
}
