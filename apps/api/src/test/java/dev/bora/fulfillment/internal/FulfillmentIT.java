package dev.bora.fulfillment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

import dev.bora.couriers.CourierCommand;
import dev.bora.couriers.CourierDirectory;
import dev.bora.couriers.CourierPositionsPersisted;
import dev.bora.couriers.CourierStatus;
import dev.bora.couriers.PositionSample;
import dev.bora.demo.DemoLimits;
import dev.bora.dispatch.PendingOfferCleanup;
import dev.bora.orders.OrderConfirmationCodes;
import dev.bora.orders.OrderStatus;
import dev.bora.orders.OrderWorkflow;
import dev.bora.routing.GeoPoint;
import dev.bora.routing.Route;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingService;
import dev.bora.routing.SnappedPoint;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "bora.fulfillment.reconciliation-enabled=false",
      "bora.dispatch.expiry-enabled=false",
      "bora.couriers.flush-initial-delay-ms=3600000"
    })
@Import(FulfillmentIT.TestDependencies.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FulfillmentIT {
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private static final UUID STORE = UUID.fromString("20000000-0000-4000-8000-000000000001");
  private static final GeoPoint PICKUP = new GeoPoint(-23.5614, -46.6559);
  private static final GeoPoint FAR = new GeoPoint(-23.55, -46.64);
  private static final GeoPoint DROPOFF = new GeoPoint(-23.559, -46.65);

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @Autowired MutableClock clock;
  @Autowired RoutingService routing;
  @Autowired CourierDirectory couriers;
  @Autowired OrderWorkflow orders;
  @Autowired OrderConfirmationCodes codes;
  @Autowired DeliveryProgress progress;
  @Autowired PendingOfferCleanup cleanup;
  @Autowired DataSource dataSource;
  @Autowired ApplicationEventPublisher events;
  @Autowired PlatformTransactionManager manager;
  @Autowired JsonMapper mapper;
  @MockitoSpyBean JdbcClient jdbc;
  @MockitoBean DemoLimits limits;
  @LocalServerPort int port;

  @BeforeEach
  void resetDependencies() {
    clock.at.set(NOW);
    reset(routing);
    doAnswer(
            call -> {
              GeoPoint point = call.getArgument(0);
              return new SnappedPoint(point, point, 0);
            })
        .when(routing)
        .snap(any());
    doAnswer(
            call -> {
              GeoPoint origin = call.getArgument(0), target = call.getArgument(1);
              return new Route(
                  List.of(origin, target),
                  1_000,
                  Duration.ofSeconds(120),
                  Duration.ofSeconds(40),
                  new SnappedPoint(origin, origin, 0),
                  new SnappedPoint(target, target, 0));
            })
        .when(routing)
        .route(any(), any());
  }

  @Test
  void arrivesTracksPicksUpAndDeliversPrivateOrderWithCodeWithoutLeakingIt() throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    UUID id = fixture(browser, OrderStatus.ASSIGNED, false);
    setPosition(browser.courierId, NOW, PICKUP);
    var samples =
        List.of(new PositionSample(NOW.minusSeconds(1), FAR), new PositionSample(NOW, PICKUP));
    publishPositions(browser.courierId, samples);
    assertThat(orders.find(id).orElseThrow().status()).isEqualTo(OrderStatus.AT_STORE);
    assertThat(tracks(id)).isEqualTo(2);
    publishPositions(browser.courierId, samples);
    assertThat(tracks(id)).isEqualTo(2);
    assertThat(eventCount(id, "ORDER_AT_STORE")).isEqualTo(1);
    JsonNode work = json(browser.get("/api/v1/courier/work"));
    assertThat(work.path("order").path("status").asString()).isEqualTo("AT_STORE");
    assertThat(work.path("order").path("confirmationRequired").asBoolean()).isTrue();
    assertThat(work.toString())
        .doesNotContain("confirmationCode", "confirmationCodeHash", "publicCode");
    assertThat(json(browser.get("/api/v1/courier/route")).path("destination").asString())
        .isEqualTo("PICKUP");
    assertThat(
            browser
                .post("/api/v1/orders/" + id + "/pickup", "{\"expectedVersion\":1}")
                .statusCode())
        .isEqualTo(200);
    assertThat(
            browser
                .post("/api/v1/orders/" + id + "/pickup", "{\"expectedVersion\":1}")
                .statusCode())
        .isEqualTo(409);
    assertThat(json(browser.get("/api/v1/courier/route")).path("destination").asString())
        .isEqualTo("DROPOFF");
    String wrong =
        String.format(
            java.util.Locale.ROOT, "%06d", (Integer.parseInt(codes.code(id)) + 1) % 1_000_000);
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return null;
            })
        .when(limits)
        .checkDeliveryConfirmation(browser.courierId);
    assertThat(browser.post("/api/v1/orders/" + id + "/deliver", delivery(2, wrong)).statusCode())
        .isEqualTo(409);
    assertThat(orders.find(id).orElseThrow().status()).isEqualTo(OrderStatus.PICKED_UP);
    assertThat(couriers.find(browser.courierId).status()).isEqualTo(CourierStatus.BUSY);
    assertThat(
            browser
                .post("/api/v1/orders/" + id + "/deliver", delivery(2, codes.code(id)))
                .statusCode())
        .isEqualTo(200);
    assertThat(orders.find(id).orElseThrow().status()).isEqualTo(OrderStatus.DELIVERED);
    assertThat(couriers.find(browser.courierId).status()).isEqualTo(CourierStatus.AVAILABLE);
    assertThat(json(browser.get("/api/v1/courier/work")).path("order").isNull()).isTrue();
    assertThat(eventCount(id, "ORDER_DELIVERED")).isEqualTo(1);
  }

  @Test
  void onlyPublicSimulatedOrdersBypassConfirmationEvenWithTheSameSimulatedPickup()
      throws Exception {
    Browser privateCourier = new Browser();
    privateCourier.login("COURIER");
    UUID privateId = fixture(privateCourier, OrderStatus.PICKED_UP, false);
    assertThat(browserStatus(privateCourier, privateId, 0, null)).isEqualTo(409);
    Browser publicCourier = new Browser();
    publicCourier.login("COURIER");
    UUID publicId = fixture(publicCourier, OrderStatus.PICKED_UP, true);
    assertThat(browserStatus(publicCourier, publicId, 0, null)).isEqualTo(200);
    assertThat(couriers.find(publicCourier.courierId).status()).isEqualTo(CourierStatus.AVAILABLE);
  }

  @Test
  void rejectsForeignCouriersWrongRolesAndMalformedVersions() throws Exception {
    Browser owner = new Browser();
    owner.login("COURIER");
    UUID id = fixture(owner, OrderStatus.AT_STORE, false);
    Browser foreign = new Browser();
    foreign.login("COURIER");
    assertThat(
            foreign
                .post("/api/v1/orders/" + id + "/pickup", "{\"expectedVersion\":0}")
                .statusCode())
        .isEqualTo(404);
    assertThat(
            foreign
                .post("/api/v1/orders/" + id + "/deliver", delivery(0, codes.code(id)))
                .statusCode())
        .isEqualTo(404);
    assertThat(json(foreign.get("/api/v1/courier/work")).path("order").isNull()).isTrue();
    assertThat(owner.post("/api/v1/orders/" + id + "/pickup", "{}").statusCode()).isEqualTo(400);
    assertThat(
            owner.post("/api/v1/orders/" + id + "/pickup", "{\"expectedVersion\":-1}").statusCode())
        .isEqualTo(400);
    assertThat(owner.post("/api/v1/orders/" + id + "/deliver", delivery(0, "123x")).statusCode())
        .isEqualTo(400);
    Browser store = new Browser();
    store.login("STORE");
    assertThat(store.get("/api/v1/courier/work").statusCode()).isEqualTo(403);
    assertThat(
            store.post("/api/v1/orders/" + id + "/pickup", "{\"expectedVersion\":0}").statusCode())
        .isEqualTo(403);
    assertThat(new Browser().get("/api/v1/courier/work").statusCode()).isEqualTo(401);
  }

  @Test
  void reconcilesCommittedPositionWithoutNotificationAndRejectsPreassignmentOrStaleArrival()
      throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    UUID id = fixture(browser, OrderStatus.ASSIGNED, false);
    setPosition(browser.courierId, NOW.minusSeconds(31), PICKUP);
    progress.reconcile();
    assertThat(orders.find(id).orElseThrow().status()).isEqualTo(OrderStatus.ASSIGNED);
    assertThat(tracks(id)).isZero();
    setPosition(browser.courierId, NOW, PICKUP);
    clock.at.set(NOW.plusSeconds(31));
    progress.reconcile();
    assertThat(orders.find(id).orElseThrow().status()).isEqualTo(OrderStatus.ASSIGNED);
    assertThat(tracks(id)).isEqualTo(1);
    setPosition(browser.courierId, NOW.plusSeconds(31), PICKUP);
    progress.reconcile();
    progress.reconcile();
    assertThat(orders.find(id).orElseThrow().status()).isEqualTo(OrderStatus.AT_STORE);
    assertThat(eventCount(id, "ORDER_AT_STORE")).isEqualTo(1);
    assertThat(tracks(id)).isEqualTo(2);
  }

  @Test
  void keepsPersistedPositionWhenProgressTransactionFailsAndRecoversFromDatabase()
      throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    UUID id = fixture(browser, OrderStatus.ASSIGNED, false);
    setPosition(browser.courierId, NOW, PICKUP);
    jdbc.sql(
            """
        CREATE FUNCTION reject_test_arrival() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
        IF NEW.order_id='%s'::uuid AND NEW.type='ORDER_AT_STORE' THEN
          RAISE EXCEPTION 'Falha de chegada provocada pelo teste'; END IF; RETURN NEW; END $$
        """
                .formatted(id))
        .update();
    jdbc.sql(
            "CREATE TRIGGER test_arrival_failure BEFORE INSERT ON order_events FOR EACH ROW EXECUTE FUNCTION reject_test_arrival()")
        .update();
    try {
      publishPositions(browser.courierId, List.of(new PositionSample(NOW, PICKUP)));
      assertThat(couriers.find(browser.courierId).locationUpdatedAt()).isEqualTo(NOW);
      assertThat(orders.find(id).orElseThrow().status()).isEqualTo(OrderStatus.ASSIGNED);
      assertThat(tracks(id)).isZero();
    } finally {
      jdbc.sql("DROP TRIGGER test_arrival_failure ON order_events").update();
      jdbc.sql("DROP FUNCTION reject_test_arrival()").update();
    }
    progress.reconcile();
    assertThat(orders.find(id).orElseThrow().status()).isEqualTo(OrderStatus.AT_STORE);
    assertThat(tracks(id)).isEqualTo(1);
  }

  @Test
  void cancelsAndReassignsBusyCouriersInsideTheSameTransaction() throws Exception {
    Browser cancel = new Browser();
    cancel.login("COURIER");
    UUID canceled = fixture(cancel, OrderStatus.ASSIGNED, false);
    UUID courier = cancel.courierId;
    cancel.login("OPERATIONS");
    assertThat(
            cancel
                .post("/api/v1/orders/" + canceled + "/cancel", "{\"expectedVersion\":0}")
                .statusCode())
        .isEqualTo(200);
    assertThat(couriers.find(courier).status()).isEqualTo(CourierStatus.AVAILABLE);
    Browser reassign = new Browser();
    reassign.login("COURIER");
    UUID reassigned = fixture(reassign, OrderStatus.ASSIGNED, false);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              var locked = orders.lockForUpdate(reassigned);
              orders.reassign(reassigned, locked.version(), "test:reassign");
            });
    assertThat(orders.find(reassigned).orElseThrow().status()).isEqualTo(OrderStatus.SEARCHING);
    assertThat(couriers.find(reassign.courierId).status()).isEqualTo(CourierStatus.AVAILABLE);
  }

  @Test
  void rollsBackDeliveryAndItsEventWhenCourierReleaseFails() throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    UUID id = fixture(browser, OrderStatus.PICKED_UP, false);
    jdbc.sql(
            """
        CREATE FUNCTION reject_test_release() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
        IF NEW.id='%s'::uuid AND NEW.status='AVAILABLE' THEN
          RAISE EXCEPTION 'Falha de liberação provocada pelo teste'; END IF; RETURN NEW; END $$
        """
                .formatted(browser.courierId))
        .update();
    jdbc.sql(
            "CREATE TRIGGER test_release_failure BEFORE UPDATE ON couriers FOR EACH ROW EXECUTE FUNCTION reject_test_release()")
        .update();
    try {
      assertThat(browserStatus(browser, id, 0, codes.code(id))).isEqualTo(500);
      assertThat(orders.find(id).orElseThrow().status()).isEqualTo(OrderStatus.PICKED_UP);
      assertThat(orders.find(id).orElseThrow().version()).isZero();
      assertThat(couriers.find(browser.courierId).status()).isEqualTo(CourierStatus.BUSY);
      assertThat(eventCount(id, "ORDER_DELIVERED")).isZero();
    } finally {
      jdbc.sql("DROP TRIGGER test_release_failure ON couriers").update();
      jdbc.sql("DROP FUNCTION reject_test_release()").update();
    }
    assertThat(browserStatus(browser, id, 0, codes.code(id))).isEqualTo(200);
  }

  @Test
  @Timeout(30)
  void onlyOneConcurrentDeliveryChangesTheOrderAndReleasesTheCourier() throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    UUID id = fixture(browser, OrderStatus.PICKED_UP, false);
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var calls =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  index ->
                      executor.submit(
                          () -> {
                            barrier.await(10, TimeUnit.SECONDS);
                            return browserStatus(browser, id, 0, codes.code(id));
                          }))
              .toList();
      assertThat(
              List.of(
                      calls.get(0).get(15, TimeUnit.SECONDS),
                      calls.get(1).get(15, TimeUnit.SECONDS))
                  .stream()
                  .sorted()
                  .toList())
          .containsExactly(200, 409);
    }
    assertThat(eventCount(id, "ORDER_DELIVERED")).isEqualTo(1);
    assertThat(couriers.find(browser.courierId).version()).isEqualTo(1);
    assertThat(couriers.find(browser.courierId).status()).isEqualTo(CourierStatus.AVAILABLE);
  }

  @Test
  void workUsesOneSnapshotWhenAssignmentCommitsBetweenItsQueries() throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    UUID id = fixture(browser, OrderStatus.SEARCHING, false);
    AtomicBoolean assigned = new AtomicBoolean();
    doAnswer(
            call -> {
              String sql = call.getArgument(0);
              if (sql.contains("FROM orders o JOIN stores")
                  && sql.contains("o.courier_id=:courier")
                  && assigned.compareAndSet(false, true)) {
                TransactionTemplate writer = new TransactionTemplate(manager);
                writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                writer.executeWithoutResult(
                    status -> {
                      orders.lockForUpdate(id);
                      couriers.transition(browser.courierId, new CourierCommand.ReceiveOffer());
                      couriers.transition(browser.courierId, new CourierCommand.AcceptOffer());
                      orders.assign(id, 0, browser.courierId, "test:concurrent-assign");
                    });
              }
              return call.callRealMethod();
            })
        .when(jdbc)
        .sql(anyString());
    JsonNode work = json(browser.get("/api/v1/courier/work"));
    assertThat(assigned).isTrue();
    assertThat(work.path("courier").path("status").asString()).isEqualTo("AVAILABLE");
    assertThat(work.path("order").isNull()).isTrue();
    assertThat(couriers.find(browser.courierId).status()).isEqualTo(CourierStatus.BUSY);
    assertThat(orders.find(id).orElseThrow().status()).isEqualTo(OrderStatus.ASSIGNED);
  }

  @Test
  void rejectsStaleRouteAndMapsRoutingFailures() throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    assertThat(browser.get("/api/v1/courier/route").statusCode()).isEqualTo(409);
    fixture(browser, OrderStatus.ASSIGNED, false);
    setPosition(browser.courierId, NOW.minusSeconds(31), FAR);
    assertThat(browser.get("/api/v1/courier/route").statusCode()).isEqualTo(409);
    setPosition(browser.courierId, NOW, FAR);
    doAnswer(
            call -> {
              throw new RoutingException(RoutingException.Reason.NO_ROUTE, "Sem rota.");
            })
        .when(routing)
        .route(any(), any());
    assertThat(browser.get("/api/v1/courier/route").statusCode()).isEqualTo(422);
    doAnswer(
            call -> {
              throw new RoutingException(RoutingException.Reason.GRAPH_UNAVAILABLE, "Sem grafo.");
            })
        .when(routing)
        .route(any(), any());
    assertThat(browser.get("/api/v1/courier/route").statusCode()).isEqualTo(503);
  }

  @Test
  void ignoresDelayedPositionEventsFromAnEarlierAvailabilityEpoch() throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    UUID id = fixture(browser, OrderStatus.ASSIGNED, false);
    Long oldEpoch =
        jdbc.sql("SELECT position_epoch FROM couriers WHERE id=:id")
            .param("id", browser.courierId)
            .query(Long.class)
            .single();
    var delayed =
        new CourierPositionsPersisted(
            Map.of(browser.courierId, List.of(new PositionSample(NOW, PICKUP))),
            Map.of(browser.courierId, oldEpoch));
    jdbc.sql("UPDATE couriers SET position_epoch=position_epoch+1 WHERE id=:id")
        .param("id", browser.courierId)
        .update();
    events.publishEvent(delayed);
    assertThat(tracks(id)).isZero();
    assertThat(orders.find(id).orElseThrow().status()).isEqualTo(OrderStatus.ASSIGNED);
    setPosition(browser.courierId, NOW, FAR);
    publishPositions(browser.courierId, List.of(new PositionSample(NOW, FAR)));
    assertThat(tracks(id)).isEqualTo(1);
    assertThat(orders.find(id).orElseThrow().status()).isEqualTo(OrderStatus.ASSIGNED);
  }

  @Test
  void cancelsPendingOfferAndOfferedCourierThroughThePreviousReleaseOrderEndpoint()
      throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    UUID order = fixture(browser, OrderStatus.SEARCHING, false), courier = browser.courierId;
    UUID offer = pendingOffer(order, courier, NOW.plusSeconds(25));
    browser.login("OPERATIONS");
    assertThat(
            browser
                .post("/api/v1/orders/" + order + "/cancel", "{\"expectedVersion\":0}")
                .statusCode())
        .isEqualTo(200);
    assertThat(orders.find(order).orElseThrow().status()).isEqualTo(OrderStatus.CANCELED);
    assertThat(offerStatus(offer)).isEqualTo("CANCELED");
    assertThat(couriers.find(courier).status()).isEqualTo(CourierStatus.AVAILABLE);
  }

  @Test
  void reconstructsPendingDeadlinesFromDatabaseAfterRestartAndExpiresExactlyOnce()
      throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    UUID order = fixture(browser, OrderStatus.SEARCHING, false);
    UUID offer = pendingOffer(order, browser.courierId, NOW.plusSeconds(25));
    assertThat(cleanup.expireDueOffers()).isZero();
    assertThat(offerStatus(offer)).isEqualTo("PENDING");
    clock.at.set(NOW.plusSeconds(25));
    var restarted = new PendingOfferCleanup(jdbc, couriers, clock, manager);
    assertThat(restarted.expireDueOffers()).isEqualTo(1);
    assertThat(restarted.expireDueOffers()).isZero();
    assertThat(offerStatus(offer)).isEqualTo("EXPIRED");
    assertThat(orders.find(order).orElseThrow().status()).isEqualTo(OrderStatus.SEARCHING);
    assertThat(couriers.find(browser.courierId).status()).isEqualTo(CourierStatus.AVAILABLE);
    assertThat(
            jdbc.sql("SELECT next_attempt_at FROM orders WHERE id=:id")
                .param("id", order)
                .query(OffsetDateTime.class)
                .single()
                .toInstant())
        .isEqualTo(clock.instant());
    assertThat(
            jdbc.sql(
                    "SELECT responded_at>=expires_at AND responded_at>=created_at FROM offers WHERE id=:id")
                .param("id", offer)
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  @Timeout(20)
  void skipsAnExpiredOrderHeldByAnotherTransaction() throws Exception {
    Browser first = new Browser();
    first.login("COURIER");
    UUID lockedOrder = fixture(first, OrderStatus.SEARCHING, false);
    UUID lockedOffer = pendingOffer(lockedOrder, first.courierId, NOW.minusSeconds(1));
    Browser second = new Browser();
    second.login("COURIER");
    UUID freeOrder = fixture(second, OrderStatus.SEARCHING, false);
    UUID freeOffer = pendingOffer(freeOrder, second.courierId, NOW.minusSeconds(1));
    try (var held = dataSource.getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      held.setAutoCommit(false);
      try (var statement = held.prepareStatement("SELECT id FROM orders WHERE id=? FOR UPDATE")) {
        statement.setObject(1, lockedOrder);
        statement.executeQuery().close();
      }
      try {
        assertThat(executor.submit(() -> cleanup.expireDueOffers()).get(5, TimeUnit.SECONDS))
            .isEqualTo(1);
        assertThat(offerStatus(lockedOffer)).isEqualTo("PENDING");
        assertThat(offerStatus(freeOffer)).isEqualTo("EXPIRED");
      } finally {
        held.rollback();
      }
    }
    assertThat(cleanup.expireDueOffers()).isEqualTo(1);
    assertThat(offerStatus(lockedOffer)).isEqualTo("EXPIRED");
  }

  @Test
  void neverFreesABusyCourierWhenAnOldPendingOfferExpires() throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    UUID old = fixture(browser, OrderStatus.SEARCHING, false);
    UUID offer = pendingOffer(old, browser.courierId, NOW.minusSeconds(1));
    UUID active = fixture(browser, OrderStatus.ASSIGNED, false);
    assertThat(cleanup.expireDueOffers()).isEqualTo(1);
    assertThat(offerStatus(offer)).isEqualTo("EXPIRED");
    assertThat(couriers.find(browser.courierId).status()).isEqualTo(CourierStatus.BUSY);
    assertThat(orders.find(active).orElseThrow().status()).isEqualTo(OrderStatus.ASSIGNED);
  }

  @Test
  void rollsBackOrderOfferAndReservationWhenPendingCourierReleaseFails() throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    UUID order = fixture(browser, OrderStatus.SEARCHING, false), courier = browser.courierId;
    UUID offer = pendingOffer(order, courier, NOW.plusSeconds(25));
    browser.login("OPERATIONS");
    jdbc.sql(
            """
        CREATE FUNCTION reject_test_pending_release() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
        IF NEW.id='%s'::uuid AND NEW.status='AVAILABLE' THEN
          RAISE EXCEPTION 'Falha de reserva provocada pelo teste'; END IF; RETURN NEW; END $$
        """
                .formatted(courier))
        .update();
    jdbc.sql(
            "CREATE TRIGGER test_pending_release_failure BEFORE UPDATE ON couriers FOR EACH ROW EXECUTE FUNCTION reject_test_pending_release()")
        .update();
    try {
      assertThat(
              browser
                  .post("/api/v1/orders/" + order + "/cancel", "{\"expectedVersion\":0}")
                  .statusCode())
          .isEqualTo(500);
      assertThat(orders.find(order).orElseThrow().status()).isEqualTo(OrderStatus.SEARCHING);
      assertThat(orders.find(order).orElseThrow().version()).isZero();
      assertThat(offerStatus(offer)).isEqualTo("PENDING");
      assertThat(couriers.find(courier).status()).isEqualTo(CourierStatus.OFFERED);
      assertThat(eventCount(order, "ORDER_CANCELED")).isZero();
    } finally {
      jdbc.sql("DROP TRIGGER test_pending_release_failure ON couriers").update();
      jdbc.sql("DROP FUNCTION reject_test_pending_release()").update();
    }
    assertThat(
            browser
                .post("/api/v1/orders/" + order + "/cancel", "{\"expectedVersion\":0}")
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  @Timeout(20)
  void twoExpiryInstancesOnlyReleaseOneReservationOnce() throws Exception {
    Browser browser = new Browser();
    browser.login("COURIER");
    UUID order = fixture(browser, OrderStatus.SEARCHING, false);
    UUID offer = pendingOffer(order, browser.courierId, NOW.minusSeconds(1));
    var other = new PendingOfferCleanup(jdbc, couriers, clock, manager);
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return cleanup.expireDueOffers();
              });
      var second =
          executor.submit(
              () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return other.expireDueOffers();
              });
      assertThat(
              List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)).stream()
                  .sorted()
                  .toList())
          .containsExactly(0, 1);
    }
    assertThat(offerStatus(offer)).isEqualTo("EXPIRED");
    assertThat(couriers.find(browser.courierId).status()).isEqualTo(CourierStatus.AVAILABLE);
    assertThat(couriers.find(browser.courierId).version()).isEqualTo(1);
  }

  @Test
  @Timeout(20)
  void expiryDoesNotInvertTheBulkWriterCourierLockOrder() throws Exception {
    Browser first = new Browser();
    first.login("COURIER");
    Browser second = new Browser();
    second.login("COURIER");
    Browser a =
        first.courierId.toString().compareTo(second.courierId.toString()) < 0 ? first : second;
    Browser b = a == first ? second : first;
    UUID orderA = fixture(a, OrderStatus.SEARCHING, false);
    UUID orderB = fixture(b, OrderStatus.SEARCHING, false);
    UUID offerA = pendingOffer(orderA, a.courierId, NOW.minusSeconds(1));
    UUID offerB = pendingOffer(orderB, b.courierId, NOW.minusSeconds(2));
    CountDownLatch bulkHasA = new CountDownLatch(1);
    CountDownLatch expiryHasB = new CountDownLatch(1);
    CountDownLatch allowBCommit = new CountDownLatch(1);
    AtomicBoolean firstRelease = new AtomicBoolean();
    doAnswer(
            call -> {
              String sql = call.getArgument(0);
              if (sql.startsWith("UPDATE couriers SET status=:status")
                  && firstRelease.compareAndSet(false, true)) {
                expiryHasB.countDown();
                assertThat(allowBCommit.await(5, TimeUnit.SECONDS)).isTrue();
              }
              return call.callRealMethod();
            })
        .when(jdbc)
        .sql(anyString());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var bulk =
          executor.submit(
              () -> {
                try (var connection = dataSource.getConnection()) {
                  connection.setAutoCommit(false);
                  try (var lock =
                      connection.prepareStatement(
                          "SELECT id FROM couriers WHERE id=? FOR UPDATE")) {
                    lock.setObject(1, a.courierId);
                    lock.executeQuery().close();
                    bulkHasA.countDown();
                    assertThat(expiryHasB.await(5, TimeUnit.SECONDS)).isTrue();
                    allowBCommit.countDown();
                    lock.setObject(1, b.courierId);
                    lock.executeQuery().close();
                  }
                  connection.commit();
                }
                return true;
              });
      assertThat(bulkHasA.await(5, TimeUnit.SECONDS)).isTrue();
      var expiry = executor.submit(cleanup::expireDueOffers);
      assertThat(expiry.get(10, TimeUnit.SECONDS)).isEqualTo(2);
      assertThat(bulk.get(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      expiryHasB.countDown();
      allowBCommit.countDown();
    }
    assertThat(offerStatus(offerA)).isEqualTo("EXPIRED");
    assertThat(offerStatus(offerB)).isEqualTo("EXPIRED");
    assertThat(couriers.find(a.courierId).status()).isEqualTo(CourierStatus.AVAILABLE);
    assertThat(couriers.find(b.courierId).status()).isEqualTo(CourierStatus.AVAILABLE);
  }

  private UUID pendingOffer(UUID order, UUID courier, Instant expires) {
    UUID id = UUID.randomUUID();
    jdbc.sql("UPDATE couriers SET status='OFFERED' WHERE id=:id").param("id", courier).update();
    jdbc.sql(
            """
        INSERT INTO offers(id,order_id,courier_id,status,eta_to_pickup_s,created_at,expires_at)
        VALUES(:id,:order,:courier,'PENDING',60,:created,:expires)
        """)
        .param("id", id)
        .param("order", order)
        .param("courier", courier)
        .param("created", timestamp(expires.minusSeconds(30)))
        .param("expires", timestamp(expires))
        .update();
    return id;
  }

  private String offerStatus(UUID offer) {
    return jdbc.sql("SELECT status FROM offers WHERE id=:id")
        .param("id", offer)
        .query(String.class)
        .single();
  }

  private void publishPositions(UUID courier, List<PositionSample> samples) {
    Long epoch =
        jdbc.sql("SELECT position_epoch FROM couriers WHERE id=:id")
            .param("id", courier)
            .query(Long.class)
            .single();
    events.publishEvent(
        new CourierPositionsPersisted(Map.of(courier, samples), Map.of(courier, epoch)));
  }

  private int browserStatus(Browser browser, UUID id, long version, String code) throws Exception {
    return browser.post("/api/v1/orders/" + id + "/deliver", delivery(version, code)).statusCode();
  }

  private String delivery(long version, String code) {
    return "{\"expectedVersion\":"
        + version
        + (code == null ? "" : ",\"confirmationCode\":\"" + code + "\"")
        + "}";
  }

  private UUID fixture(Browser browser, OrderStatus state, boolean simulated) {
    UUID id = UUID.randomUUID();
    UUID context =
        simulated
            ? null
            : jdbc.sql("SELECT context_id FROM users WHERE id=:id")
                .param("id", browser.userId)
                .query(UUID.class)
                .single();
    jdbc.sql(
            """
        INSERT INTO orders(id,public_code,store_id,dropoff,status,courier_id,confirmation_code_hash,
          sla_deadline,next_attempt_at,created_at,assigned_at,at_store_at,picked_up_at,visitor_context_id,owner_user_id)
        VALUES(:id,:code,:store,ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,:status,:courier,:hash,
          :sla,:attempt,:created,:assigned,:atStore,:picked,:context,:owner)
        """)
        .param("id", id)
        .param("code", UUID.randomUUID().toString())
        .param("store", STORE)
        .param("lon", DROPOFF.longitude())
        .param("lat", DROPOFF.latitude())
        .param("status", state.name())
        .param("courier", state == OrderStatus.SEARCHING ? null : browser.courierId)
        .param("hash", simulated ? null : codes.hash(id))
        .param("sla", timestamp(NOW.plusSeconds(1_800)))
        .param("attempt", state == OrderStatus.SEARCHING ? timestamp(NOW.minusSeconds(120)) : null)
        .param("created", timestamp(NOW.minusSeconds(120)))
        .param("assigned", state == OrderStatus.SEARCHING ? null : timestamp(NOW.minusSeconds(30)))
        .param(
            "atStore",
            state == OrderStatus.AT_STORE || state == OrderStatus.PICKED_UP
                ? timestamp(NOW.minusSeconds(20))
                : null)
        .param("picked", state == OrderStatus.PICKED_UP ? timestamp(NOW.minusSeconds(10)) : null)
        .param("context", context)
        .param("owner", simulated ? null : browser.userId)
        .update();
    setPosition(browser.courierId, NOW.minusSeconds(2), FAR);
    jdbc.sql("UPDATE couriers SET status=:status WHERE id=:id")
        .param("id", browser.courierId)
        .param("status", state == OrderStatus.SEARCHING ? "AVAILABLE" : "BUSY")
        .update();
    return id;
  }

  private void setPosition(UUID id, Instant at, GeoPoint point) {
    jdbc.sql(
            """
        UPDATE couriers SET location=ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,
          location_updated_at=:at WHERE id=:id
        """)
        .param("id", id)
        .param("at", timestamp(at))
        .param("lon", point.longitude())
        .param("lat", point.latitude())
        .update();
  }

  private long tracks(UUID id) {
    return jdbc.sql("SELECT count(*) FROM delivery_tracks WHERE order_id=:id")
        .param("id", id)
        .query(Long.class)
        .single();
  }

  private long eventCount(UUID id, String type) {
    return jdbc.sql("SELECT count(*) FROM order_events WHERE order_id=:id AND type=:type")
        .param("id", id)
        .param("type", type)
        .query(Long.class)
        .single();
  }

  private static OffsetDateTime timestamp(Instant at) {
    return at.atOffset(ZoneOffset.UTC);
  }

  private JsonNode json(HttpResponse<String> response) {
    return mapper.readTree(response.body());
  }

  private final class Browser {
    private final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    private String csrfHeader, csrf;
    private UUID userId, courierId;

    void login(String role) throws Exception {
      refreshCsrf();
      var response = post("/api/v1/auth/demo", "{\"role\":\"" + role + "\"}");
      assertThat(response.statusCode()).isEqualTo(200);
      JsonNode user = json(response).path("user");
      userId = UUID.fromString(user.path("id").asString());
      courierId =
          user.path("courierId").isNull()
              ? null
              : UUID.fromString(user.path("courierId").asString());
      refreshCsrf();
    }

    void refreshCsrf() throws Exception {
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
  static class TestDependencies {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }

    @Bean
    @Primary
    RoutingService fulfillmentRoutingMock() {
      return mock(RoutingService.class);
    }
  }

  static class MutableClock extends Clock {
    final AtomicReference<Instant> at = new AtomicReference<>(NOW);

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return at.get();
    }
  }
}
