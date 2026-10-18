package dev.bora.orders.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.bora.orders.InvalidOrderTransition;
import dev.bora.orders.OrderChange;
import dev.bora.orders.OrderStatus;
import dev.bora.orders.OrderVersionConflict;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(OrderLifecycleIT.FixedClockConfiguration.class)
class OrderLifecycleIT {

  private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  private static final Instant CREATED_AT = NOW.minusSeconds(60);
  private static final UUID STORE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
  private static final double LONGITUDE = -46.6333;
  private static final double LATITUDE = -23.5505;

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @Autowired private OrderLifecycleService lifecycle;
  @Autowired private JdbcClient jdbc;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JsonMapper jsonMapper;

  @Test
  void deliversWithOneVersionAndEventPerTransitionAndPreservesGeography() {
    UUID orderId = createOrder();
    UUID courierId = createCourier();
    String originalDropoff = snapshot(orderId).dropoff();

    inTransaction(
        () -> {
          OrderEntity order = entityManager.find(OrderEntity.class, orderId);
          assertThat(order.id()).isEqualTo(orderId);
          assertThat(order.version()).isZero();
          assertThat(order.state().status()).isEqualTo(OrderStatus.SEARCHING);
          assertDropoff(order.dropoff());
          return null;
        });

    assertChange(
        lifecycle.assign(orderId, 0, courierId, "  operations:test  "),
        orderId,
        OrderStatus.ASSIGNED,
        courierId,
        1);
    assertChange(
        lifecycle.arriveAtStore(orderId, 1, "courier:test"),
        orderId,
        OrderStatus.AT_STORE,
        courierId,
        2);
    assertChange(
        lifecycle.pickUp(orderId, 2, "courier:test"), orderId, OrderStatus.PICKED_UP, courierId, 3);
    assertChange(
        lifecycle.deliver(orderId, 3, "courier:test"),
        orderId,
        OrderStatus.DELIVERED,
        courierId,
        4);

    Snapshot delivered = snapshot(orderId);
    assertThat(delivered.assignedAt()).isEqualTo(NOW);
    assertThat(delivered.atStoreAt()).isEqualTo(NOW);
    assertThat(delivered.pickedUpAt()).isEqualTo(NOW);
    assertThat(delivered.deliveredAt()).isEqualTo(NOW);
    assertThat(delivered.canceledAt()).isNull();
    assertThat(delivered.nextAttemptAt()).isNull();
    assertThat(delivered.dropoff()).isEqualTo(originalDropoff);
    inTransaction(
        () -> {
          assertDropoff(entityManager.find(OrderEntity.class, orderId).dropoff());
          return null;
        });

    List<Event> events = events(orderId);
    assertThat(events).extracting(Event::version).containsExactly(1L, 2L, 3L, 4L);
    assertEvent(
        events.get(0), "ORDER_ASSIGNED", "SEARCHING", "ASSIGNED", "Assign", courierId, null);
    assertEvent(
        events.get(1),
        "ORDER_AT_STORE",
        "ASSIGNED",
        "AT_STORE",
        "ArriveAtStore",
        courierId,
        courierId);
    assertEvent(
        events.get(2), "ORDER_PICKED_UP", "AT_STORE", "PICKED_UP", "PickUp", courierId, courierId);
    assertEvent(
        events.get(3),
        "ORDER_DELIVERED",
        "PICKED_UP",
        "DELIVERED",
        "Deliver",
        courierId,
        courierId);
    assertThat(events.get(0).actor()).isEqualTo("operations:test");
  }

  @Test
  void distinguishesStoreCancellationFromOperationsWithoutMutatingRejectedTransitions() {
    UUID searchingId = createOrder();
    assertChange(
        lifecycle.cancelByStore(searchingId, 0, "store:test"),
        searchingId,
        OrderStatus.CANCELED,
        null,
        1);
    assertEvent(
        events(searchingId).getFirst(),
        "ORDER_CANCELED",
        "SEARCHING",
        "CANCELED",
        "CancelByStore",
        null,
        null);

    UUID assignedId = createOrder();
    UUID assignedCourier = createCourier();
    lifecycle.assign(assignedId, 0, assignedCourier, "operations:test");
    assertChange(
        lifecycle.cancelByStore(assignedId, 1, "store:test"),
        assignedId,
        OrderStatus.CANCELED,
        assignedCourier,
        2);
    assertThat(snapshot(assignedId).canceledAt()).isEqualTo(NOW);

    UUID atStoreId = createOrder();
    UUID atStoreCourier = createCourier();
    lifecycle.assign(atStoreId, 0, atStoreCourier, "operations:test");
    lifecycle.arriveAtStore(atStoreId, 1, "courier:test");
    Snapshot before = snapshot(atStoreId);
    List<Event> previousEvents = events(atStoreId);

    assertThatThrownBy(() -> lifecycle.cancelByStore(atStoreId, 2, "store:test"))
        .isInstanceOf(InvalidOrderTransition.class);
    assertThat(snapshot(atStoreId)).isEqualTo(before);
    assertThat(events(atStoreId)).isEqualTo(previousEvents);

    assertChange(
        lifecycle.cancelByOperations(atStoreId, 2, "operations:test"),
        atStoreId,
        OrderStatus.CANCELED,
        atStoreCourier,
        3);
    assertThat(snapshot(atStoreId).canceledAt()).isEqualTo(NOW);
    assertEvent(
        events(atStoreId).getLast(),
        "ORDER_CANCELED",
        "AT_STORE",
        "CANCELED",
        "CancelByOperations",
        atStoreCourier,
        atStoreCourier);
  }

  @Test
  void reassignsAndKeepsThePreviousCourierInHistory() {
    UUID orderId = createOrder(8_000);
    UUID firstCourier = createCourier();
    UUID nextCourier = createCourier();
    lifecycle.assign(orderId, 0, firstCourier, "operations:test");
    lifecycle.arriveAtStore(orderId, 1, "courier:test");
    List<Event> firstAssignment = events(orderId);

    assertChange(
        lifecycle.reassign(orderId, 2, "operations:test"), orderId, OrderStatus.SEARCHING, null, 3);
    Snapshot searching = snapshot(orderId);
    assertThat(searching.searchRadiusM()).isEqualTo(2_000);
    assertThat(searching.nextAttemptAt()).isEqualTo(NOW);
    assertThat(searching.assignedAt()).isNull();
    assertThat(searching.atStoreAt()).isNull();
    assertThat(searching.pickedUpAt()).isNull();
    assertThat(searching.deliveredAt()).isNull();
    assertThat(searching.canceledAt()).isNull();
    assertEvent(
        events(orderId).getLast(),
        "ORDER_REASSIGNED",
        "AT_STORE",
        "SEARCHING",
        "Reassign",
        null,
        firstCourier);

    assertChange(
        lifecycle.assign(orderId, 3, nextCourier, "operations:test"),
        orderId,
        OrderStatus.ASSIGNED,
        nextCourier,
        4);
    List<Event> history = events(orderId);
    assertThat(history.subList(0, 2)).isEqualTo(firstAssignment);
    assertEvent(
        history.getLast(), "ORDER_ASSIGNED", "SEARCHING", "ASSIGNED", "Assign", nextCourier, null);
  }

  @Test
  void rejectsAnOutdatedSnapshotWithoutChangingTheOrderOrItsEvents() {
    UUID orderId = createOrder();
    lifecycle.assign(orderId, 0, createCourier(), "operations:test");
    Snapshot before = snapshot(orderId);
    List<Event> previousEvents = events(orderId);

    assertThatThrownBy(() -> lifecycle.arriveAtStore(orderId, 0, "courier:test"))
        .isInstanceOf(OrderVersionConflict.class);

    assertThat(snapshot(orderId)).isEqualTo(before);
    assertThat(events(orderId)).isEqualTo(previousEvents);
  }

  @Test
  void hydratesJsonEventsWithoutAttemptingAnUpdateAgainstTheAppendOnlyTrigger() {
    UUID orderId = createOrder();
    UUID courierId = createCourier();
    lifecycle.assign(orderId, 0, courierId, "operations:test");
    Event original = events(orderId).getFirst();
    assertThat(
            jdbc.sql("SELECT jsonb_typeof(payload) FROM order_events WHERE id = :id")
                .param("id", original.id())
                .query(String.class)
                .single())
        .isEqualTo("object");

    inTransaction(
        () -> {
          OrderEventEntity event = entityManager.find(OrderEventEntity.class, original.id());
          assertThat(event).isNotNull();
          Map<?, ?> payload =
              entityManager
                  .createQuery(
                      "SELECT event.payload FROM OrderEventEntity event WHERE event.id = :id",
                      Map.class)
                  .setParameter("id", original.id())
                  .getSingleResult();
          assertThat(payload.get("from")).isEqualTo("SEARCHING");
          assertThat(payload.get("to")).isEqualTo("ASSIGNED");
          assertThat(payload.get("courierId")).isEqualTo(courierId.toString());
          assertThat(((Number) payload.get("version")).longValue()).isEqualTo(1L);
          assertThat(payload.values())
              .allMatch(value -> value instanceof String || value instanceof Number);
          SessionFactoryImplementor sessionFactory =
              entityManager.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class);
          assertThat(
                  sessionFactory
                      .getSessionFactoryOptions()
                      .getJsonFormatMapper()
                      .getClass()
                      .getSimpleName())
              .isEqualTo("Jackson3JsonFormatMapper");

          entityManager.flush();
          return null;
        });

    assertThat(events(orderId)).containsExactly(original);
    assertThat(snapshot(orderId).version()).isEqualTo(1L);
  }

  @Test
  void rollsBackTheFlushedOrderWhenTheDatabaseRejectsItsEvent() {
    UUID orderId = createOrder();
    UUID courierId = createCourier();
    Snapshot before = snapshot(orderId);
    String functionName = "test_reject_event_" + orderId.toString().replace("-", "");
    String triggerName = "test_event_failure_" + orderId.toString().replace("-", "");
    jdbc.sql(
            """
            CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              RAISE EXCEPTION 'Falha de evento provocada pelo teste.' USING ERRCODE = '23514';
            END;
            $$
            """
                .formatted(functionName))
        .update();
    try {
      jdbc.sql(
              """
              CREATE TRIGGER %s BEFORE INSERT ON order_events
              FOR EACH ROW WHEN (NEW.order_id = '%s'::uuid)
              EXECUTE FUNCTION %s()
              """
                  .formatted(triggerName, orderId, functionName))
          .update();

      Throwable failure =
          catchThrowable(() -> lifecycle.assign(orderId, 0, courierId, "operations:test"));

      assertThat(failure).isNotNull();
      assertThat(hasSqlState(failure, "23514")).isTrue();
      assertThat(snapshot(orderId)).isEqualTo(before);
      assertThat(events(orderId)).isEmpty();
    } finally {
      jdbc.sql("DROP TRIGGER IF EXISTS " + triggerName + " ON order_events").update();
      jdbc.sql("DROP FUNCTION IF EXISTS " + functionName + "()").update();
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void commitsOnlyOneConcurrentTransitionAfterBothTransactionsReadTheSameVersion()
      throws Exception {
    UUID orderId = createOrder();
    UUID courierId = createCourier();
    CyclicBarrier bothLoaded = new CyclicBarrier(2);

    List<Attempt> attempts;
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Attempt> assignment =
          executor.submit(
              () ->
                  concurrentAttempt(
                      orderId,
                      bothLoaded,
                      () -> lifecycle.assign(orderId, 0, courierId, "operations:race")));
      Future<Attempt> cancellation =
          executor.submit(
              () ->
                  concurrentAttempt(
                      orderId,
                      bothLoaded,
                      () -> lifecycle.cancelByStore(orderId, 0, "store:race")));
      try {
        attempts =
            List.of(assignment.get(20, TimeUnit.SECONDS), cancellation.get(20, TimeUnit.SECONDS));
      } finally {
        assignment.cancel(true);
        cancellation.cancel(true);
      }
    }

    List<OrderChange> successes =
        attempts.stream()
            .filter(attempt -> attempt.failure() == null)
            .map(Attempt::change)
            .toList();
    List<Throwable> failures =
        attempts.stream().map(Attempt::failure).filter(failure -> failure != null).toList();
    assertThat(successes).hasSize(1);
    assertThat(failures).hasSize(1);
    assertThat(failures.getFirst())
        .isInstanceOf(OrderVersionConflict.class)
        .hasCauseInstanceOf(OptimisticLockException.class);

    OrderChange winner = successes.getFirst();
    assertChange(winner, orderId, winner.status(), winner.courierId(), 1);
    Event event = events(orderId).getFirst();
    assertThat(event.payload().path("from").asString()).isEqualTo("SEARCHING");
    assertThat(event.payload().path("to").asString()).isEqualTo(winner.status().name());
    assertThat(event.type()).isEqualTo("ORDER_" + winner.status().name());
  }

  private Attempt concurrentAttempt(
      UUID orderId, CyclicBarrier bothLoaded, Supplier<OrderChange> command) {
    try {
      OrderChange change =
          inTransaction(
              () -> {
                entityManager.createNativeQuery("SET LOCAL lock_timeout = '5s'").executeUpdate();
                entityManager
                    .createNativeQuery("SET LOCAL statement_timeout = '10s'")
                    .executeUpdate();
                OrderEntity order = entityManager.find(OrderEntity.class, orderId);
                assertThat(order.version()).isZero();
                try {
                  bothLoaded.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException("A disputa foi interrompida.", interrupted);
                } catch (Exception failure) {
                  throw new IllegalStateException(
                      "As duas transações não carregaram o pedido.", failure);
                }
                return command.get();
              });
      return new Attempt(change, null);
    } catch (RuntimeException failure) {
      return new Attempt(null, failure);
    }
  }

  private UUID createOrder() {
    return createOrder(2_000);
  }

  private UUID createOrder(int searchRadiusM) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO orders (
              id, public_code, store_id, dropoff, status, sla_deadline,
              next_attempt_at, search_radius_m, created_at, version
            ) VALUES (
              :id, :code, :store, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
              'SEARCHING', :deadline, :created, :radius, :created, 0
            )
            """)
        .param("id", id)
        .param("code", id.toString().replace("-", ""))
        .param("store", STORE_ID)
        .param("longitude", LONGITUDE)
        .param("latitude", LATITUDE)
        .param("deadline", NOW.plusSeconds(1_800).atOffset(ZoneOffset.UTC))
        .param("created", CREATED_AT.atOffset(ZoneOffset.UTC))
        .param("radius", searchRadiusM)
        .update();
    return id;
  }

  private UUID createCourier() {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO couriers (id, name, status, location, location_updated_at, version)
            VALUES (
              :id, 'Entregador de teste', 'AVAILABLE',
              ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography, :created, 0
            )
            """)
        .param("id", id)
        .param("longitude", LONGITUDE)
        .param("latitude", LATITUDE)
        .param("created", CREATED_AT.atOffset(ZoneOffset.UTC))
        .update();
    return id;
  }

  private Snapshot snapshot(UUID orderId) {
    return jdbc.sql(
            """
            SELECT status, courier_id, version, next_attempt_at, search_radius_m,
              assigned_at, at_store_at, picked_up_at, delivered_at, canceled_at,
              ST_AsEWKT(dropoff::geometry) AS dropoff
            FROM orders WHERE id = :id
            """)
        .param("id", orderId)
        .query(
            (row, index) ->
                new Snapshot(
                    OrderStatus.valueOf(row.getString("status")),
                    row.getObject("courier_id", UUID.class),
                    row.getLong("version"),
                    instant(row, "next_attempt_at"),
                    row.getInt("search_radius_m"),
                    instant(row, "assigned_at"),
                    instant(row, "at_store_at"),
                    instant(row, "picked_up_at"),
                    instant(row, "delivered_at"),
                    instant(row, "canceled_at"),
                    row.getString("dropoff")))
        .single();
  }

  private List<Event> events(UUID orderId) {
    return jdbc.sql(
            """
            SELECT id, type, payload::text AS payload, actor, occurred_at, order_version
            FROM order_events WHERE order_id = :id ORDER BY id
            """)
        .param("id", orderId)
        .query(
            (row, index) ->
                new Event(
                    row.getLong("id"),
                    row.getString("type"),
                    jsonMapper.readTree(row.getString("payload")),
                    row.getString("actor"),
                    instant(row, "occurred_at"),
                    row.getLong("order_version")))
        .list();
  }

  private void assertChange(
      OrderChange change, UUID id, OrderStatus status, UUID courierId, long version) {
    assertThat(change).isEqualTo(new OrderChange(id, status, courierId, version, NOW));
    Snapshot persisted = snapshot(id);
    assertThat(persisted.status()).isEqualTo(status);
    assertThat(persisted.courierId()).isEqualTo(courierId);
    assertThat(persisted.version()).isEqualTo(version);
    assertThat(events(id)).hasSize((int) version);
  }

  private static void assertEvent(
      Event event,
      String type,
      String from,
      String to,
      String command,
      UUID courierId,
      UUID previousCourierId) {
    assertThat(event.type()).isEqualTo(type);
    assertThat(event.occurredAt()).isEqualTo(NOW);
    assertThat(event.payload().path("from").asString()).isEqualTo(from);
    assertThat(event.payload().path("to").asString()).isEqualTo(to);
    assertThat(event.payload().path("command").asString()).isEqualTo(command);
    assertThat(event.payload().path("version").asLong()).isEqualTo(event.version());
    if (courierId == null) {
      assertThat(event.payload().has("courierId")).isFalse();
    } else {
      assertThat(event.payload().path("courierId").asString()).isEqualTo(courierId.toString());
    }
    if (previousCourierId == null) {
      assertThat(event.payload().has("previousCourierId")).isFalse();
    } else {
      assertThat(event.payload().path("previousCourierId").asString())
          .isEqualTo(previousCourierId.toString());
    }
  }

  private static void assertDropoff(Point dropoff) {
    assertThat(dropoff.getSRID()).isEqualTo(4326);
    assertThat(dropoff.getX()).isEqualTo(LONGITUDE);
    assertThat(dropoff.getY()).isEqualTo(LATITUDE);
  }

  private <T> T inTransaction(Supplier<T> action) {
    return new TransactionTemplate(transactionManager).execute(status -> action.get());
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static boolean hasSqlState(Throwable failure, String state) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private record Snapshot(
      OrderStatus status,
      UUID courierId,
      long version,
      Instant nextAttemptAt,
      int searchRadiusM,
      Instant assignedAt,
      Instant atStoreAt,
      Instant pickedUpAt,
      Instant deliveredAt,
      Instant canceledAt,
      String dropoff) {}

  private record Event(
      long id, String type, JsonNode payload, String actor, Instant occurredAt, long version) {}

  private record Attempt(OrderChange change, Throwable failure) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    @Bean
    @Primary
    Clock testClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
