package dev.bora.dispatch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.bora.couriers.CourierDirectory;
import dev.bora.demo.DemoLimits;
import dev.bora.routing.*;
import java.net.*;
import java.net.http.*;
import java.sql.Connection;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "bora.dispatch.worker-enabled=false", "bora.dispatch.expiry-enabled=false",
      "bora.fulfillment.reconciliation-enabled=false",
          "bora.couriers.flush-initial-delay-ms=3600000"
    })
@Import(DispatchApiIT.Dependencies.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DispatchApiIT {
  static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  static final UUID STORE = UUID.fromString("20000000-0000-4000-8000-000000000001");
  static final GeoPoint PICKUP = new GeoPoint(-23.5614, -46.6559);
  static final GeoPoint NEAR = new GeoPoint(-23.5615, -46.6559);
  static final GeoPoint FAR = new GeoPoint(-23.5624, -46.6559);

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @Autowired DispatchCoordinator dispatch;
  @Autowired PendingOfferCleanup cleanup;
  @Autowired MutableClock clock;
  @Autowired RoutingService routing;
  @Autowired CourierDirectory couriers;
  @Autowired PlatformTransactionManager manager;
  @Autowired DataSource dataSource;
  @Autowired JsonMapper mapper;
  @MockitoSpyBean JdbcClient jdbc;
  @MockitoBean DispatchActivation activation;
  @MockitoBean DemoLimits limits;
  @LocalServerPort int port;

  @BeforeEach
  void prepare() {
    clock.at.set(NOW);
    reset(routing);
    reset(activation);
    when(activation.allowsCreation()).thenReturn(true);
    jdbc.sql("UPDATE orders SET next_attempt_at=:next WHERE status='SEARCHING'")
        .param("next", time(NOW.plusSeconds(3600)))
        .update();
    jdbc.sql(
            "UPDATE offers SET status='CANCELED',responded_at=GREATEST(created_at,:now) WHERE status='PENDING'")
        .param("now", time(NOW))
        .update();
    jdbc.sql("UPDATE couriers SET status='OFFLINE' WHERE status IN ('AVAILABLE','OFFERED')")
        .update();
    when(routing.snap(any()))
        .thenAnswer(
            call -> {
              GeoPoint p = call.getArgument(0);
              return new SnappedPoint(p, p, 0);
            });
    when(routing.route(any(), any()))
        .thenAnswer(
            call -> {
              GeoPoint a = call.getArgument(0), b = call.getArgument(1);
              return new Route(
                  List.of(a, b),
                  1000,
                  Duration.ofSeconds(120),
                  Duration.ofSeconds(40),
                  new SnappedPoint(a, a, 0),
                  new SnappedPoint(b, b, 0));
            });
  }

  @Test
  void reservesByRouteEtaAcceptsAtomicallyAndDoesNotLeakSecrets() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    UUID order = order(null, true, 10);
    online(actor.courierId, FAR);
    UUID closer = simulatedCourier(NEAR);
    doAnswer(call -> route(FAR, call.getArgument(1), 10)).when(routing).route(eq(FAR), any());
    assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
    UUID offer = offer(order);
    assertThat(value("SELECT courier_id::text FROM offers WHERE id=:id", offer))
        .isEqualTo(actor.courierId.toString());
    JsonNode inbox = json(actor.get("/api/v1/courier/offer"));
    assertThat(inbox.path("offer").path("etaToPickupSeconds").asInt()).isEqualTo(10);
    assertThat(inbox.path("serverTime").asString()).isEqualTo(NOW.toString());
    assertThat(inbox.toString())
        .doesNotContain("confirmationCode", "confirmationCodeHash", "publicCode");
    assertThat(actor.post("/api/v1/offers/" + offer + "/accept", "").statusCode()).isEqualTo(204);
    assertThat(value("SELECT status FROM orders WHERE id=:id", order)).isEqualTo("ASSIGNED");
    assertThat(value("SELECT status FROM offers WHERE id=:id", offer)).isEqualTo("ACCEPTED");
    assertThat(value("SELECT status FROM couriers WHERE id=:id", actor.courierId))
        .isEqualTo("BUSY");
    assertThat(couriers.find(closer).status().name()).isEqualTo("AVAILABLE");
    assertThat(actor.post("/api/v1/offers/" + offer + "/accept", "").statusCode()).isEqualTo(409);
    assertThat(json(actor.get("/api/v1/courier/offer")).path("offer").isNull()).isTrue();
    assertThat(count(order, "ORDER_ASSIGNED")).isEqualTo(1);
    assertThat(count(order, "OFFER_ACCEPTED")).isEqualTo(1);
  }

  @Test
  void comparesExactRouteDurationsBeforeRoundingThePublicEta() {
    UUID order = order(null, true, 10);
    UUID slower = simulatedCourier(NEAR), faster = simulatedCourier(FAR);
    UUID slowId = UUID.fromString("10000000-0000-4000-8000-000000000010");
    UUID fastId = UUID.fromString("90000000-0000-4000-8000-000000000010");
    jdbc.sql("UPDATE couriers SET id=:next WHERE id=:id")
        .param("id", slower)
        .param("next", slowId)
        .update();
    jdbc.sql("UPDATE couriers SET id=:next WHERE id=:id")
        .param("id", faster)
        .param("next", fastId)
        .update();
    doAnswer(
            call -> {
              GeoPoint a = call.getArgument(0), b = call.getArgument(1);
              long millis = a.equals(FAR) ? 10100 : 10900;
              return new Route(
                  List.of(a, b),
                  1000,
                  Duration.ofMillis(millis * 3),
                  Duration.ofMillis(millis),
                  new SnappedPoint(a, a, 0),
                  new SnappedPoint(b, b, 0));
            })
        .when(routing)
        .route(any(), any());
    assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
    UUID offer = offer(order);
    assertThat(value("SELECT courier_id::text FROM offers WHERE id=:id", offer))
        .isEqualTo(fastId.toString());
    assertThat(value("SELECT eta_to_pickup_s::text FROM offers WHERE id=:id", offer))
        .isEqualTo("11");
  }

  @Test
  void rejectsAndExcludesPreviousCourierOnlyForThatOrder() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    UUID order = order(actor, false, 10);
    online(actor.courierId, NEAR);
    assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
    assertThat(actor.post("/api/v1/offers/" + offer(order) + "/reject", "").statusCode())
        .isEqualTo(204);
    assertThat(dispatch.dispatchAvailableOrders()).isZero();
    assertThat(value("SELECT status FROM couriers WHERE id=:id", actor.courierId))
        .isEqualTo("AVAILABLE");
    UUID another = order(actor, false, 10);
    assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
    assertThat(value("SELECT status FROM offers WHERE id=:id", offer(another)))
        .isEqualTo("PENDING");
    assertThat(count(order, "OFFER_REJECTED")).isEqualTo(1);
  }

  @Test
  void lateAcceptAndRejectCommitExpiryBeforeReturningConflict() throws Exception {
    for (String action : List.of("accept", "reject")) {
      Browser actor = new Browser();
      actor.login("COURIER");
      UUID order = order(actor, false, 10);
      online(actor.courierId, NEAR);
      assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
      UUID offer = offer(order);
      clock.at.set(clock.instant().plusSeconds(30));
      assertThat(actor.post("/api/v1/offers/" + offer + "/" + action, "").statusCode())
          .isEqualTo(409);
      assertThat(value("SELECT status FROM offers WHERE id=:id", offer)).isEqualTo("EXPIRED");
      assertThat(value("SELECT status FROM couriers WHERE id=:id", actor.courierId))
          .isEqualTo("AVAILABLE");
      assertThat(value("SELECT status FROM orders WHERE id=:id", order)).isEqualTo("SEARCHING");
      assertThat(count(order, "OFFER_EXPIRED")).isEqualTo(1);
      assertThat(dispatch.dispatchAvailableOrders()).isZero();
    }
  }

  @Test
  void enforcesRoleOwnershipCsrfAndMalformedVersion() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    UUID order = order(actor, false, 10);
    online(actor.courierId, NEAR);
    dispatch.dispatchAvailableOrders();
    UUID offer = offer(order);
    Browser stranger = new Browser();
    stranger.login("COURIER");
    assertThat(stranger.post("/api/v1/offers/" + offer + "/accept", "").statusCode())
        .isEqualTo(404);
    assertThat(json(stranger.get("/api/v1/courier/offer")).path("offer").isNull()).isTrue();
    actor.login("STORE");
    assertThat(actor.get("/api/v1/courier/offer").statusCode()).isEqualTo(403);
    assertThat(actor.post("/api/v1/offers/" + offer + "/accept", "").statusCode()).isEqualTo(403);
    actor.login("OPERATIONS");
    assertThat(actor.post("/api/v1/ops/orders/" + order + "/reassign", "{}").statusCode())
        .isEqualTo(400);
    assertThat(
            actor
                .post("/api/v1/ops/orders/" + order + "/reassign", "{\"expectedVersion\":-1}")
                .statusCode())
        .isEqualTo(400);
    assertThat(stranger.get("/api/v1/courier/offer").statusCode()).isEqualTo(200);
    assertThat(new Browser().get("/api/v1/courier/offer").statusCode()).isEqualTo(401);
  }

  @Test
  void reassignReleasesBusyCourierAndDoesNotOfferItTheSameOrderAgain() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    UUID order = order(actor, false, 10);
    online(actor.courierId, NEAR);
    dispatch.dispatchAvailableOrders();
    UUID courier = actor.courierId;
    assertThat(actor.post("/api/v1/offers/" + offer(order) + "/accept", "").statusCode())
        .isEqualTo(204);
    actor.login("OPERATIONS");
    assertThat(
            actor
                .post("/api/v1/ops/orders/" + order + "/reassign", "{\"expectedVersion\":0}")
                .statusCode())
        .isEqualTo(409);
    Browser foreign = new Browser();
    foreign.login("OPERATIONS");
    assertThat(
            foreign
                .post("/api/v1/ops/orders/" + order + "/reassign", "{\"expectedVersion\":1}")
                .statusCode())
        .isEqualTo(404);
    assertThat(
            actor
                .post("/api/v1/ops/orders/" + order + "/reassign", "{\"expectedVersion\":1}")
                .statusCode())
        .isEqualTo(204);
    assertThat(value("SELECT status FROM couriers WHERE id=:id", courier)).isEqualTo("AVAILABLE");
    assertThat(value("SELECT status FROM orders WHERE id=:id", order)).isEqualTo("SEARCHING");
    assertThat(dispatch.dispatchAvailableOrders()).isZero();
    assertThat(count(order, "ORDER_REASSIGNED")).isEqualTo(1);
  }

  @Test
  void failsClosedOnActivationAndSkipsRevokedExpiredOrPrivateNullContexts() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    UUID order = order(actor, false, 10);
    online(actor.courierId, NEAR);
    when(activation.allowsCreation()).thenReturn(false);
    assertThat(dispatch.dispatchAvailableOrders()).isZero();
    assertThat(offers(order)).isZero();
    when(activation.allowsCreation()).thenReturn(true);
    jdbc.sql("UPDATE demo_contexts SET expires_at=created_at WHERE id=:id")
        .param("id", actor.contextId)
        .update();
    simulatedCourier(NEAR);
    assertThat(dispatch.dispatchAvailableOrders()).isZero();
    assertThat(offers(order)).isZero();
    Browser expired = new Browser();
    expired.login("COURIER");
    UUID old = order(expired, false, 10);
    jdbc.sql("UPDATE demo_contexts SET created_at=:created,expires_at=:at WHERE id=:id")
        .param("id", expired.contextId)
        .param("created", time(NOW.minusSeconds(60)))
        .param("at", time(NOW.minusSeconds(1)))
        .update();
    assertThat(dispatch.dispatchAvailableOrders()).isZero();
    assertThat(offers(old)).isZero();
    UUID privateStore = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO stores(id,name,location,service_area_id,simulated) SELECT :id,'Privada',location,service_area_id,false FROM stores WHERE id=:seed")
        .param("id", privateStore)
        .param("seed", STORE)
        .update();
    UUID noContext = order(null, true, 10);
    jdbc.sql("UPDATE orders SET store_id=:store WHERE id=:id")
        .param("store", privateStore)
        .param("id", noContext)
        .update();
    assertThat(dispatch.dispatchAvailableOrders()).isZero();
    assertThat(offers(noContext)).isZero();
  }

  @Test
  void keepsForeignCouriersOutOfPrivateOrdersAndAllowsThemInPublicOrders() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    UUID order = order(actor, false, 10);
    Browser foreign = new Browser();
    foreign.login("COURIER");
    online(foreign.courierId, NEAR);
    assertThat(dispatch.dispatchAvailableOrders()).isZero();
    assertThat(offers(order)).isZero();
    UUID publicOrder = order(null, true, 10);
    assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
    assertThat(value("SELECT courier_id::text FROM offers WHERE id=:id", offer(publicOrder)))
        .isEqualTo(foreign.courierId.toString());
    assertThat(json(foreign.get("/api/v1/courier/offer")).path("offer").path("orderId").asString())
        .isEqualTo(publicOrder.toString());
    assertThat(foreign.post("/api/v1/offers/" + offer(publicOrder) + "/accept", "").statusCode())
        .isEqualTo(204);
    assertThat(offers(order)).isZero();
    UUID simulated = simulatedCourier(NEAR);
    UUID anotherPublic = order(null, true, 10);
    assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
    assertThat(value("SELECT courier_id::text FROM offers WHERE id=:id", offer(anotherPublic)))
        .isEqualTo(simulated.toString());
    assertThat(offers(order)).isZero();
  }

  @Test
  void reservesPrivateSearchCourierWhileOtherContextsKeepReceivingOlderPublicOrders()
      throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    online(actor.courierId, NEAR);
    Browser publicActor = new Browser();
    publicActor.login("COURIER");
    online(publicActor.courierId, FAR);
    UUID publicOrder = order(null, true, 60);
    jdbc.sql("UPDATE orders SET next_attempt_at=:next WHERE id=:id")
        .param("next", time(NOW.minusSeconds(1)))
        .param("id", publicOrder)
        .update();
    UUID privateOrder = order(actor, false, 10);
    doAnswer(call -> route(NEAR, call.getArgument(1), 10)).when(routing).route(eq(NEAR), any());

    assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(2);
    assertThat(value("SELECT courier_id::text FROM offers WHERE id=:id", offer(publicOrder)))
        .isEqualTo(publicActor.courierId.toString());
    assertThat(value("SELECT courier_id::text FROM offers WHERE id=:id", offer(privateOrder)))
        .isEqualTo(actor.courierId.toString());
    assertThat(json(actor.get("/api/v1/courier/offer")).path("offer").path("orderId").asString())
        .isEqualTo(privateOrder.toString());
    assertThat(actor.post("/api/v1/offers/" + offer(privateOrder) + "/accept", "").statusCode())
        .isEqualTo(204);
    assertThat(value("SELECT status FROM orders WHERE id=:id", privateOrder)).isEqualTo("ASSIGNED");
    assertThat(
            publicActor.post("/api/v1/offers/" + offer(publicOrder) + "/accept", "").statusCode())
        .isEqualTo(204);
  }

  @Test
  @Timeout(20)
  void skipsContextWithUncommittedPrivateCreationWithoutWaitingWhileHoldingCourier()
      throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    online(actor.courierId, NEAR);
    UUID publicOrder = order(null, true, 60);
    UUID privateOrder;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var lock =
          connection.prepareStatement("SELECT id FROM demo_contexts WHERE id=? FOR UPDATE")) {
        lock.setObject(1, actor.contextId);
        lock.executeQuery().close();
      }
      privateOrder = insertUncommittedPrivateOrder(connection, actor);
      var concurrent = executor.submit(dispatch::dispatchAvailableOrders);
      assertThat(concurrent.get(5, TimeUnit.SECONDS)).isZero();
      assertThat(offers(publicOrder)).isZero();
      assertThat(value("SELECT status FROM couriers WHERE id=:id", actor.courierId))
          .isEqualTo("AVAILABLE");
      connection.commit();
    }
    assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
    assertThat(offers(publicOrder)).isZero();
    assertThat(value("SELECT courier_id::text FROM offers WHERE id=:id", offer(privateOrder)))
        .isEqualTo(actor.courierId.toString());
  }

  @Test
  void preservesExistingPublicOfferWhenPrivateSearchIsCreatedLater() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    online(actor.courierId, NEAR);
    UUID publicOrder = order(null, true, 60);
    assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
    UUID existing = offer(publicOrder);
    UUID privateOrder = order(actor, false, 10);
    assertThat(dispatch.dispatchAvailableOrders()).isZero();
    assertThat(value("SELECT status FROM offers WHERE id=:id", existing)).isEqualTo("PENDING");
    assertThat(offers(privateOrder)).isZero();
    assertThat(actor.post("/api/v1/offers/" + existing + "/accept", "").statusCode())
        .isEqualTo(204);
    assertThat(value("SELECT status FROM orders WHERE id=:id", publicOrder)).isEqualTo("ASSIGNED");
  }

  @Test
  void expandsRadiusRetriesAndAppendsOnlyOneThreeMinuteAlert() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    UUID order = order(actor, false, 200);
    for (int radius : List.of(4000, 8000, 8000)) {
      assertThat(dispatch.dispatchAvailableOrders()).isZero();
      assertThat(value("SELECT search_radius_m::text FROM orders WHERE id=:id", order))
          .isEqualTo("" + radius);
      assertThat(
              jdbc.sql("SELECT next_attempt_at FROM orders WHERE id=:id")
                  .param("id", order)
                  .query(OffsetDateTime.class)
                  .single()
                  .toInstant())
          .isEqualTo(clock.instant().plusSeconds(5));
      clock.at.set(clock.instant().plusSeconds(5));
    }
    assertThat(count(order, "ORDER_SEARCH_DELAYED")).isEqualTo(1);
  }

  @Test
  void unavailableRouteRetriesWithoutReservingCourier() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    UUID order = order(actor, false, 10);
    online(actor.courierId, NEAR);
    doThrow(new RoutingException(RoutingException.Reason.NO_ROUTE, "Sem rota."))
        .when(routing)
        .route(any(), any());
    assertThat(dispatch.dispatchAvailableOrders()).isZero();
    assertThat(offers(order)).isZero();
    assertThat(value("SELECT status FROM couriers WHERE id=:id", actor.courierId))
        .isEqualTo("AVAILABLE");
  }

  @Test
  void insertionFailureRollsBackReservationSchedulingAndAudit() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    UUID order = order(actor, false, 10);
    online(actor.courierId, NEAR);
    jdbc.sql(
            "CREATE FUNCTION fail_dispatch_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test'; END; $$")
        .update();
    jdbc.sql(
            "CREATE TRIGGER fail_dispatch_insert BEFORE INSERT ON offers FOR EACH ROW EXECUTE FUNCTION fail_dispatch_insert()")
        .update();
    try {
      assertThatThrownBy(dispatch::dispatchAvailableOrders).isInstanceOf(RuntimeException.class);
    } finally {
      jdbc.sql("DROP TRIGGER fail_dispatch_insert ON offers").update();
      jdbc.sql("DROP FUNCTION fail_dispatch_insert()").update();
    }
    assertThat(offers(order)).isZero();
    assertThat(count(order, "OFFER_CREATED")).isZero();
    assertThat(value("SELECT status FROM couriers WHERE id=:id", actor.courierId))
        .isEqualTo("AVAILABLE");
    assertThat(dispatch.dispatchAvailableOrders()).isEqualTo(1);
  }

  @Test
  @Timeout(25)
  void deadlineIsCheckedAfterWaitingForOrderLock() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    UUID order = order(actor, false, 10);
    online(actor.courierId, NEAR);
    dispatch.dispatchAvailableOrders();
    UUID offer = offer(order);
    CountDownLatch waiting = new CountDownLatch(1);
    doAnswer(
            call -> {
              String sql = call.getArgument(0);
              if (sql.contains("WHERE o.id=:id FOR UPDATE OF o")) waiting.countDown();
              return call.callRealMethod();
            })
        .when(jdbc)
        .sql(anyString());
    try (var connection = dataSource.getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement("SELECT id FROM orders WHERE id=? FOR UPDATE")) {
        statement.setObject(1, order);
        statement.executeQuery().close();
      }
      var response = executor.submit(() -> actor.post("/api/v1/offers/" + offer + "/accept", ""));
      assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
      clock.at.set(NOW.plusSeconds(31));
      connection.commit();
      assertThat(response.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
    }
    assertThat(value("SELECT status FROM offers WHERE id=:id", offer)).isEqualTo("EXPIRED");
    assertThat(value("SELECT status FROM couriers WHERE id=:id", actor.courierId))
        .isEqualTo("AVAILABLE");
  }

  @Test
  void newCleanupInstanceUsesPersistedDeadlineAndRetainsAtomicHistory() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    UUID order = order(actor, false, 10);
    online(actor.courierId, NEAR);
    dispatch.dispatchAvailableOrders();
    UUID offer = offer(order);
    clock.at.set(NOW.plusSeconds(30));
    var restarted = new PendingOfferCleanup(jdbc, couriers, clock, manager);
    assertThat(restarted.expireDueOffers()).isEqualTo(1);
    assertThat(cleanup.expireDueOffers()).isZero();
    assertThat(value("SELECT status FROM offers WHERE id=:id", offer)).isEqualTo("EXPIRED");
    assertThat(count(order, "OFFER_EXPIRED")).isEqualTo(1);
  }

  private Route route(GeoPoint a, GeoPoint b, long seconds) {
    return new Route(
        List.of(a, b),
        1000,
        Duration.ofSeconds(seconds * 3),
        Duration.ofSeconds(seconds),
        new SnappedPoint(a, a, 0),
        new SnappedPoint(b, b, 0));
  }

  private UUID order(Browser browser, boolean simulated, long age) {
    UUID id = UUID.randomUUID();
    Instant now = clock.instant();
    jdbc.sql(
            """
      INSERT INTO orders(id,public_code,store_id,dropoff,status,sla_deadline,next_attempt_at,created_at,visitor_context_id,owner_user_id)
      VALUES(:id,:code,:store,ST_SetSRID(ST_MakePoint(-46.65,-23.559),4326)::geography,'SEARCHING',:sla,:now,:created,:context,:owner)
      """)
        .param("id", id)
        .param("code", UUID.randomUUID().toString())
        .param("store", STORE)
        .param("sla", time(now.plusSeconds(1800)))
        .param("now", time(now))
        .param("created", time(now.minusSeconds(age)))
        .param("context", simulated ? null : browser.contextId)
        .param("owner", simulated ? null : browser.userId)
        .update();
    return id;
  }

  private UUID insertUncommittedPrivateOrder(Connection connection, Browser browser)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var statement =
        connection.prepareStatement(
            """
        INSERT INTO orders(id,public_code,store_id,dropoff,status,sla_deadline,next_attempt_at,created_at,visitor_context_id,owner_user_id)
        VALUES(?,?,?,ST_SetSRID(ST_MakePoint(-46.65,-23.559),4326)::geography,'SEARCHING',?,?,?,?,?)
        """)) {
      statement.setObject(1, id);
      statement.setString(2, UUID.randomUUID().toString());
      statement.setObject(3, STORE);
      statement.setObject(4, time(clock.instant().plusSeconds(1800)));
      statement.setObject(5, time(clock.instant()));
      statement.setObject(6, time(clock.instant().minusSeconds(10)));
      statement.setObject(7, browser.contextId);
      statement.setObject(8, browser.userId);
      statement.executeUpdate();
    }
    return id;
  }

  private void online(UUID courier, GeoPoint point) {
    jdbc.sql(
            "UPDATE couriers SET status='AVAILABLE',location=ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,location_updated_at=:now WHERE id=:id")
        .param("id", courier)
        .param("lon", point.longitude())
        .param("lat", point.latitude())
        .param("now", time(clock.instant()))
        .update();
  }

  private UUID simulatedCourier(GeoPoint point) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO couriers(id,name,status,simulated) VALUES(:id,'Simulado','OFFLINE',true)")
        .param("id", id)
        .update();
    online(id, point);
    return id;
  }

  private UUID offer(UUID order) {
    return jdbc.sql("SELECT id FROM offers WHERE order_id=:id ORDER BY created_at DESC LIMIT 1")
        .param("id", order)
        .query(UUID.class)
        .single();
  }

  private long offers(UUID order) {
    return jdbc.sql("SELECT count(*) FROM offers WHERE order_id=:id")
        .param("id", order)
        .query(Long.class)
        .single();
  }

  private long count(UUID order, String type) {
    return jdbc.sql("SELECT count(*) FROM order_events WHERE order_id=:id AND type=:type")
        .param("id", order)
        .param("type", type)
        .query(Long.class)
        .single();
  }

  private String value(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query(String.class).single();
  }

  private static OffsetDateTime time(Instant now) {
    return now.atOffset(ZoneOffset.UTC);
  }

  private JsonNode json(HttpResponse<String> response) {
    return mapper.readTree(response.body());
  }

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
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json");
      if (csrf != null) request.header(csrfHeader, csrf);
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
      return mock(RoutingService.class);
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
