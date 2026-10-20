package dev.bora.dispatch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.bora.couriers.CourierDirectory;
import dev.bora.demo.DemoLimits;
import dev.bora.orders.OrderWorkflow;
import dev.bora.orders.TrainingOrders;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
@Import(TrainingOrderApiIT.Dependencies.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TrainingOrderApiIT {
  static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  static final GeoPoint PICKUP = new GeoPoint(-23.5614, -46.6559);
  static final String TRAINING = "/api/v1/courier/training-order";

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @Autowired MutableClock clock;
  @Autowired RoutingService routing;
  @Autowired JsonMapper mapper;
  @Autowired JdbcClient jdbc;
  @Autowired TrainingOrders orders;
  @Autowired OrderWorkflow workflow;
  @Autowired PlatformTransactionManager manager;
  @Autowired PendingOfferCleanup cleanup;
  @Autowired CourierDirectory couriers;
  @MockitoSpyBean OfferAudit audit;
  @MockitoBean DispatchActivation activation;
  @MockitoBean DemoLimits limits;
  @LocalServerPort int port;

  @BeforeEach
  void prepare() {
    clock.at.set(NOW);
    jdbc.sql(
            """
        UPDATE offers SET status='CANCELED',responded_at=GREATEST(created_at,:now)
        WHERE status='PENDING'
        """)
        .param("now", time(NOW))
        .update();
    jdbc.sql("UPDATE couriers SET status='OFFLINE' WHERE status IN ('AVAILABLE','OFFERED')")
        .update();
    reset(routing, activation, limits, audit);
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
                  1000,
                  Duration.ofSeconds(90),
                  Duration.ofSeconds(30),
                  new SnappedPoint(origin, origin, 0),
                  new SnappedPoint(destination, destination, 0));
            });
  }

  @Test
  void createsPrivateOrderAndOneNormalOfferReservedToItsCreator() throws Exception {
    Browser actor = courier();
    JsonNode receipt = create(actor, "first-training");
    UUID id = id(receipt, "orderId"), offer = id(receipt, "offerId");
    assertThat(receipt.path("confirmationCode").asString()).matches("[0-9]{6}");
    assertThat(Instant.parse(receipt.path("expiresAt").asString())).isEqualTo(NOW.plusSeconds(30));
    assertThat(value("SELECT visitor_context_id::text FROM orders WHERE id=:id", id))
        .isEqualTo(actor.contextId.toString());
    assertThat(value("SELECT owner_user_id::text FROM orders WHERE id=:id", id))
        .isEqualTo(actor.userId.toString());
    assertThat(value("SELECT status FROM orders WHERE id=:id", id)).isEqualTo("SEARCHING");
    assertThat(value("SELECT courier_id::text FROM offers WHERE id=:id", offer))
        .isEqualTo(actor.courierId.toString());
    assertThat(value("SELECT status FROM offers WHERE id=:id", offer)).isEqualTo("PENDING");
    assertThat(couriers.find(actor.courierId).status().name()).isEqualTo("OFFERED");
    assertThat(count(actor, "orders")).isEqualTo(1);
    assertThat(count(actor, "order_idempotency")).isEqualTo(1);
    assertThat(value("SELECT response_json::text FROM order_idempotency WHERE order_id=:id", id))
        .doesNotContain("confirmationCode", receipt.path("confirmationCode").asString());
    assertThat(value("SELECT confirmation_code_hash FROM orders WHERE id=:id", id)).isNotBlank();
    assertThat(actor.get("/api/v1/orders/" + id).statusCode()).isEqualTo(404);
    JsonNode inbox = json(actor.get("/api/v1/courier/offer"));
    assertThat(inbox.path("offer").path("id").asString()).isEqualTo(offer.toString());
    assertThat(inbox.toString()).doesNotContain("confirmationCode", "confirmation_code_hash");
  }

  @Test
  void replayKeepsIdsAndDeadlineEvenAfterAcceptanceAndSkipsNewCreationLimits() throws Exception {
    Browser actor = courier();
    JsonNode first = create(actor, "retry-training");
    UUID offer = id(first, "offerId");
    assertThat(actor.post("/api/v1/offers/" + offer + "/accept", "").statusCode()).isEqualTo(204);
    clock.at.set(NOW.plusSeconds(10));
    when(activation.allowsCreation()).thenReturn(false);
    clearInvocations(limits, routing, activation);
    JsonNode repeated = create(actor, "retry-training");
    assertThat(repeated.path("orderId").asString()).isEqualTo(first.path("orderId").asString());
    assertThat(repeated.path("offerId").asString()).isEqualTo(first.path("offerId").asString());
    assertThat(repeated.path("expiresAt").asString()).isEqualTo(first.path("expiresAt").asString());
    assertThat(repeated.path("confirmationCode").asString())
        .isEqualTo(first.path("confirmationCode").asString());
    assertThat(count(actor, "orders")).isEqualTo(1);
    verifyNoInteractions(limits, routing, activation);
  }

  @Test
  void replayAfterExpiryNeverCreatesAnotherOfferOrRenewsTheDeadline() throws Exception {
    Browser actor = courier();
    JsonNode first = create(actor, "expired-retry");
    clock.at.set(NOW.plusSeconds(31));
    JsonNode repeated = create(actor, "expired-retry");
    assertThat(repeated.path("expiresAt").asString()).isEqualTo(first.path("expiresAt").asString());
    assertThat(repeated.path("offerId").asString()).isEqualTo(first.path("offerId").asString());
    assertThat(offerCount(actor)).isEqualTo(1);
    assertThat(json(actor.get("/api/v1/courier/offer")).path("offer").isNull()).isTrue();
  }

  @Test
  void concurrentIdenticalRetriesCommitOneOrderAndOneOffer() throws Exception {
    Browser actor = courier();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var latch = new CountDownLatch(1);
      var first =
          executor.submit(
              () -> {
                latch.await();
                return actor.post(TRAINING, "", "same-key");
              });
      var second =
          executor.submit(
              () -> {
                latch.await();
                return actor.post(TRAINING, "", "same-key");
              });
      latch.countDown();
      var a = first.get(20, TimeUnit.SECONDS);
      var b = second.get(20, TimeUnit.SECONDS);
      assertThat(a.statusCode()).isEqualTo(201);
      assertThat(b.statusCode()).isEqualTo(201);
      assertThat(json(a).path("orderId").asString()).isEqualTo(json(b).path("orderId").asString());
      assertThat(json(a).path("offerId").asString()).isEqualTo(json(b).path("offerId").asString());
    }
    assertThat(count(actor, "orders")).isEqualTo(1);
    assertThat(count(actor, "order_idempotency")).isEqualTo(1);
    assertThat(offerCount(actor)).isEqualTo(1);
  }

  @Test
  void competingNewIntentCannotCreateASecondOrderForAnOfferedCourier() throws Exception {
    Browser actor = courier();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var latch = new CountDownLatch(1);
      var first =
          executor.submit(
              () -> {
                latch.await();
                return actor.post(TRAINING, "", "key-one");
              });
      var second =
          executor.submit(
              () -> {
                latch.await();
                return actor.post(TRAINING, "", "key-two");
              });
      latch.countDown();
      assertThat(
              List.of(
                  first.get(20, TimeUnit.SECONDS).statusCode(),
                  second.get(20, TimeUnit.SECONDS).statusCode()))
          .containsExactlyInAnyOrder(201, 409);
    }
    assertThat(count(actor, "orders")).isEqualTo(1);
    assertThat(count(actor, "order_idempotency")).isEqualTo(1);
    assertThat(offerCount(actor)).isEqualTo(1);
  }

  @Test
  void failureAfterOfferInsertionRollsBackOrderReservationAndIdempotencyTogether()
      throws Exception {
    Browser actor = courier();
    doThrow(new IllegalStateException("training-test-failure"))
        .when(audit)
        .write(
            any(),
            eq("OFFER_CREATED"),
            any(),
            eq(actor.courierId),
            isNull(),
            eq(OfferStatus.PENDING),
            anyString(),
            any());
    assertThat(actor.post(TRAINING, "", "rollback-key").statusCode()).isEqualTo(500);
    assertThat(count(actor, "orders")).isZero();
    assertThat(count(actor, "order_idempotency")).isZero();
    assertThat(offerCount(actor)).isZero();
    assertThat(couriers.find(actor.courierId).status().name()).isEqualTo("AVAILABLE");
    reset(audit);
    create(actor, "rollback-key");
    assertThat(count(actor, "orders")).isEqualTo(1);
    assertThat(offerCount(actor)).isEqualTo(1);
  }

  @Test
  void otherVisitorCannotDiscoverThePrivateOfferOrTrainingConfirmation() throws Exception {
    Browser actor = courier();
    JsonNode receipt = create(actor, "private-code");
    Browser outsider = courier();
    assertThat(json(outsider.get("/api/v1/courier/offer")).path("offer").isNull()).isTrue();
    assertThat(outsider.get("/api/v1/orders/" + receipt.path("orderId").asString()).statusCode())
        .isEqualTo(404);
    assertThat(
            outsider
                .post("/api/v1/offers/" + receipt.path("offerId").asString() + "/accept", "")
                .statusCode())
        .isEqualTo(404);
    JsonNode independent = create(outsider, "private-code");
    assertThat(independent.path("orderId").asString())
        .isNotEqualTo(receipt.path("orderId").asString());
  }

  @Test
  void codeIsAbsentFromOrdinaryCourierOperationsStoreAndTrackingReads() throws Exception {
    Browser actor = courier();
    JsonNode receipt = create(actor, "read-secrets");
    String id = receipt.path("orderId").asString(), offer = receipt.path("offerId").asString();
    assertThat(actor.post("/api/v1/offers/" + offer + "/accept", "").statusCode()).isEqualTo(204);
    JsonNode work = json(actor.get("/api/v1/courier/work"));
    assertThat(work.path("order").path("confirmationRequired").asBoolean()).isTrue();
    assertThat(work.toString()).doesNotContain("confirmationCode");
    JsonNode detail = json(actor.get("/api/v1/orders/" + id));
    assertThat(detail.path("confirmationCode").isNull()).isTrue();
    String publicCode = detail.path("publicCode").asString();
    String tracking = new Browser().get("/api/v1/tracking/" + publicCode).body();
    assertThat(tracking).doesNotContain("confirmationCode", "confirmation_code_hash");
    actor.login("OPERATIONS");
    assertThat(json(actor.get("/api/v1/orders/" + id)).path("confirmationCode").isNull()).isTrue();
    actor.login("STORE");
    assertThat(json(actor.get("/api/v1/orders/" + id)).path("confirmationCode").isNull()).isTrue();
    assertThat(actor.post(TRAINING, "", "read-secrets").statusCode()).isEqualTo(403);
  }

  @Test
  void limitsActiveOrdersUsingTheSamePrivateContextAsStoreCreation() throws Exception {
    Browser actor = courier();
    for (int i = 0; i < 5; i++) insertActive(actor);
    var response = actor.post(TRAINING, "", "sixth-order");
    assertThat(response.statusCode()).isEqualTo(429);
    assertThat(json(response).path("code").asString()).isEqualTo("ACTIVE_ORDER_LIMIT");
    assertThat(count(actor, "orders")).isEqualTo(5);
    assertThat(offerCount(actor)).isZero();
    assertThat(count(actor, "order_idempotency")).isZero();
  }

  @Test
  void respectsTheCreationRateAndNeverChecksItForACommittedReplay() throws Exception {
    Browser actor = courier();
    JsonNode first = create(actor, "rate-replay");
    doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "Aguarde."))
        .when(limits)
        .checkOrderCreation(actor.contextId);
    assertThat(actor.post(TRAINING, "", "new-rate-intent").statusCode()).isEqualTo(429);
    assertThat(create(actor, "rate-replay").path("orderId").asString())
        .isEqualTo(first.path("orderId").asString());
    assertThat(count(actor, "orders")).isEqualTo(1);
  }

  @Test
  void rejectsMissingInvalidKeysWrongRolesAndOfflineOrStaleCouriers() throws Exception {
    Browser actor = courier();
    assertThat(actor.post(TRAINING, "", null).statusCode()).isEqualTo(400);
    assertThat(actor.post(TRAINING, "", "two words").statusCode()).isEqualTo(400);
    assertThat(actor.post(TRAINING, "", "a".repeat(129)).statusCode()).isEqualTo(400);
    jdbc.sql("UPDATE couriers SET status='OFFLINE' WHERE id=:id")
        .param("id", actor.courierId)
        .update();
    assertThat(actor.post(TRAINING, "", "offline-training").statusCode()).isEqualTo(409);
    online(actor.courierId);
    clock.at.set(NOW.plusSeconds(31));
    var stale = actor.post(TRAINING, "", "stale-training");
    assertThat(stale.statusCode()).isEqualTo(409);
    assertThat(json(stale).path("code").asString()).isEqualTo("COURIER_POSITION_STALE");
    actor.login("STORE");
    assertThat(actor.post(TRAINING, "", "store-training").statusCode()).isEqualTo(403);
    actor.login("OPERATIONS");
    assertThat(actor.post(TRAINING, "", "ops-training").statusCode()).isEqualTo(403);
  }

  @Test
  void closedActivationAndMissingRoutesLeaveNoOrderOrReservation() throws Exception {
    Browser actor = courier();
    when(activation.allowsCreation()).thenReturn(false);
    assertThat(actor.post(TRAINING, "", "closed-gate").statusCode()).isEqualTo(503);
    when(activation.allowsCreation()).thenReturn(true);
    doThrow(new RoutingException(RoutingException.Reason.NO_ROUTE, "Sem rota."))
        .when(routing)
        .route(any(), any());
    assertThat(actor.post(TRAINING, "", "no-route").statusCode()).isEqualTo(422);
    assertThat(count(actor, "orders")).isZero();
    assertThat(count(actor, "order_idempotency")).isZero();
    assertThat(offerCount(actor)).isZero();
    assertThat(couriers.find(actor.courierId).status().name()).isEqualTo("AVAILABLE");
  }

  @Test
  void gateClosingDuringRoutePreparationRollsBackTheNewIntent() throws Exception {
    Browser actor = courier();
    doAnswer(
            call -> {
              GeoPoint origin = call.getArgument(0), destination = call.getArgument(1);
              when(activation.allowsCreation()).thenReturn(false);
              return new Route(
                  List.of(origin, destination),
                  1000,
                  Duration.ofSeconds(90),
                  Duration.ofSeconds(30),
                  new SnappedPoint(origin, origin, 0),
                  new SnappedPoint(destination, destination, 0));
            })
        .when(routing)
        .route(any(), any());
    assertThat(actor.post(TRAINING, "", "closing-gate").statusCode()).isEqualTo(503);
    assertThat(count(actor, "orders")).isZero();
    assertThat(count(actor, "order_idempotency")).isZero();
    assertThat(offerCount(actor)).isZero();
  }

  @Test
  void explicitlyEndingPendingTrainingCancelsItsOfferAndFreesTheCourierAndActiveSlot()
      throws Exception {
    Browser actor = courier();
    JsonNode receipt = create(actor, "end-pending");
    UUID order = id(receipt, "orderId"), offer = id(receipt, "offerId");
    var response = cancel(actor, order, 0);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(json(response).path("status").asString()).isEqualTo("CANCELED");
    assertThat(response.body()).doesNotContain("confirmationCode");
    assertThat(value("SELECT status FROM offers WHERE id=:id", offer)).isEqualTo("CANCELED");
    assertThat(couriers.find(actor.courierId).status().name()).isEqualTo("AVAILABLE");
    JsonNode replay = create(actor, "end-pending");
    assertThat(replay.path("status").asString()).isEqualTo("CANCELED");
    assertThat(replay.path("version").asLong()).isEqualTo(1);
    create(actor, "after-ended-training");
    assertThat(activeCount(actor)).isEqualTo(1);
  }

  @Test
  void rejectedTrainingCanBeEndedEvenWhenCourierHasNoActiveWork() throws Exception {
    Browser actor = courier();
    JsonNode receipt = create(actor, "end-rejected");
    assertThat(
            actor
                .post("/api/v1/offers/" + receipt.path("offerId").asString() + "/reject", "")
                .statusCode())
        .isEqualTo(204);
    assertThat(json(actor.get("/api/v1/courier/work")).path("order").isNull()).isTrue();
    assertThat(cancel(actor, id(receipt, "orderId"), 0).statusCode()).isEqualTo(200);
    assertThat(activeCount(actor)).isZero();
    assertThat(value("SELECT status FROM offers WHERE id=:id", id(receipt, "offerId")))
        .isEqualTo("REJECTED");
  }

  @Test
  void expiredTrainingCanBeEndedWithoutRenewingOrReplacingTheOffer() throws Exception {
    Browser actor = courier();
    JsonNode receipt = create(actor, "end-expired");
    clock.at.set(NOW.plusSeconds(31));
    assertThat(cleanup.expireDueOffers()).isEqualTo(1);
    assertThat(json(actor.get("/api/v1/courier/work")).path("order").isNull()).isTrue();
    assertThat(cancel(actor, id(receipt, "orderId"), 0).statusCode()).isEqualTo(200);
    assertThat(activeCount(actor)).isZero();
    assertThat(offerCount(actor)).isEqualTo(1);
    assertThat(value("SELECT status FROM offers WHERE id=:id", id(receipt, "offerId")))
        .isEqualTo("EXPIRED");
  }

  @Test
  void cancellationRequiresTrainingMarkerAndCreatorNotJustAVisiblePrivateOrder() throws Exception {
    Browser actor = courier();
    JsonNode receipt = create(actor, "end-private");
    UUID order = id(receipt, "orderId");
    Browser outsider = courier();
    assertThat(cancel(outsider, order, 0).statusCode()).isEqualTo(404);
    UUID normal = insertActive(actor);
    assertThat(cancel(actor, normal, 0).statusCode()).isEqualTo(404);
    assertThat(value("SELECT status FROM orders WHERE id=:id", normal)).isEqualTo("SEARCHING");
    actor.login("STORE");
    assertThat(cancel(actor, order, 0).statusCode()).isEqualTo(403);
    actor.login("OPERATIONS");
    assertThat(cancel(actor, order, 0).statusCode()).isEqualTo(403);
    assertThat(value("SELECT status FROM orders WHERE id=:id", order)).isEqualTo("SEARCHING");
  }

  @Test
  void assignedTrainingUsesOptimisticVersionAndCanBeEndedBeforePickup() throws Exception {
    Browser actor = courier();
    JsonNode receipt = create(actor, "end-assigned");
    UUID order = id(receipt, "orderId");
    assertThat(
            actor
                .post("/api/v1/offers/" + receipt.path("offerId").asString() + "/accept", "")
                .statusCode())
        .isEqualTo(204);
    var stale = cancel(actor, order, 0);
    assertThat(stale.statusCode()).isEqualTo(409);
    assertThat(json(stale).path("code").asString()).isEqualTo("ORDER_VERSION_CONFLICT");
    assertThat(value("SELECT status FROM orders WHERE id=:id", order)).isEqualTo("ASSIGNED");
    assertThat(cancel(actor, order, 1).statusCode()).isEqualTo(200);
    assertThat(couriers.find(actor.courierId).status().name()).isEqualTo("AVAILABLE");
  }

  @Test
  void atStoreCanBeEndedButPickedUpCannotBypassNormalDeliveryStateMachine() throws Exception {
    Browser actor = courier();
    JsonNode receipt = create(actor, "end-at-store");
    UUID order = id(receipt, "orderId");
    assertThat(
            actor
                .post("/api/v1/offers/" + receipt.path("offerId").asString() + "/accept", "")
                .statusCode())
        .isEqualTo(204);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            ignored -> {
              workflow.lockForUpdate(order);
              workflow.arriveAtStore(order, 1, "training-test");
            });
    assertThat(cancel(actor, order, 2).statusCode()).isEqualTo(200);
    JsonNode next = create(actor, "end-picked-up");
    UUID picked = id(next, "orderId");
    assertThat(
            actor
                .post("/api/v1/offers/" + next.path("offerId").asString() + "/accept", "")
                .statusCode())
        .isEqualTo(204);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            ignored -> {
              workflow.lockForUpdate(picked);
              workflow.arriveAtStore(picked, 1, "training-test");
              workflow.pickUp(picked, 2, "training-test");
            });
    var blocked = cancel(actor, picked, 3);
    assertThat(blocked.statusCode()).isEqualTo(409);
    assertThat(json(blocked).path("code").asString()).isEqualTo("INVALID_ORDER_TRANSITION");
    assertThat(value("SELECT status FROM orders WHERE id=:id", picked)).isEqualTo("PICKED_UP");
    assertThat(couriers.find(actor.courierId).status().name()).isEqualTo("BUSY");
  }

  @Test
  void cancellationKeepsCsrfAndBodyValidation() throws Exception {
    Browser actor = courier();
    UUID order = id(create(actor, "end-validation"), "orderId");
    String path = TRAINING + "/" + order + "/cancel";
    assertThat(actor.post(path, "{}").statusCode()).isEqualTo(400);
    assertThat(actor.post(path, "{\"expectedVersion\":-1}").statusCode()).isEqualTo(400);
    String token = actor.csrf;
    actor.csrf = null;
    assertThat(actor.post(path, "{\"expectedVersion\":0}").statusCode()).isEqualTo(403);
    actor.csrf = token;
    assertThat(value("SELECT status FROM orders WHERE id=:id", order)).isEqualTo("SEARCHING");
  }

  private HttpResponse<String> cancel(Browser actor, UUID order, long version) throws Exception {
    return actor.post(TRAINING + "/" + order + "/cancel", "{\"expectedVersion\":" + version + "}");
  }

  private long activeCount(Browser actor) {
    return jdbc.sql(
            """
        SELECT count(*) FROM orders WHERE visitor_context_id=:context
          AND status IN ('SEARCHING','ASSIGNED','AT_STORE','PICKED_UP')
        """)
        .param("context", actor.contextId)
        .query(Long.class)
        .single();
  }

  private Browser courier() throws Exception {
    Browser actor = new Browser();
    actor.login("COURIER");
    online(actor.courierId);
    return actor;
  }

  private JsonNode create(Browser actor, String key) throws Exception {
    var response = actor.post(TRAINING, "", key);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return json(response);
  }

  private UUID id(JsonNode node, String key) {
    return UUID.fromString(node.path(key).asString());
  }

  private String value(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query(String.class).single();
  }

  private long count(Browser actor, String table) {
    String column = table.equals("orders") ? "visitor_context_id" : "context_id";
    if (!Set.of("orders", "order_idempotency").contains(table))
      throw new IllegalArgumentException();
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + "=:context")
        .param("context", actor.contextId)
        .query(Long.class)
        .single();
  }

  private long offerCount(Browser actor) {
    return jdbc.sql("SELECT count(*) FROM offers WHERE courier_id=:id")
        .param("id", actor.courierId)
        .query(Long.class)
        .single();
  }

  private void online(UUID courier) {
    jdbc.sql(
            """
        UPDATE couriers SET status='AVAILABLE',
          location=ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,location_updated_at=:now
        WHERE id=:id
        """)
        .param("id", courier)
        .param("lon", PICKUP.longitude())
        .param("lat", PICKUP.latitude())
        .param("now", time(clock.instant()))
        .update();
  }

  private UUID insertActive(Browser actor) {
    UUID order = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO orders(id,public_code,store_id,dropoff,status,created_at,sla_deadline,
          next_attempt_at,visitor_context_id,owner_user_id)
        SELECT :id,:code,id,location,'SEARCHING',:now,:sla,:now,:context,:owner
        FROM stores WHERE simulated ORDER BY id LIMIT 1
        """)
        .param("id", order)
        .param("code", UUID.randomUUID().toString())
        .param("now", time(clock.instant()))
        .param("sla", time(clock.instant().plusSeconds(1800)))
        .param("context", actor.contextId)
        .param("owner", actor.userId)
        .update();
    return order;
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
