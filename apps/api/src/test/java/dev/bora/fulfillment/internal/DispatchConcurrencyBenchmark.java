package dev.bora.fulfillment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.bora.BoraApplication;
import dev.bora.dispatch.DispatchActivation;
import dev.bora.dispatch.DispatchCoordinator;
import dev.bora.dispatch.PendingOfferCleanup;
import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.orders.InvalidOrderTransition;
import dev.bora.orders.OrderVersionConflict;
import dev.bora.orders.internal.OrderLifecycleService;
import dev.bora.routing.RoutingTools;
import dev.bora.shared.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Benchmark explícito de transações concorrentes; não participa dos padrões de teste do CI. */
@Testcontainers
class DispatchConcurrencyBenchmark {
  private static final int ROUNDS_PER_FAMILY = 2_500;
  private static final String MANIFEST = "routing/sp-fixture-manifest.json";
  private static final Path GRAPHS =
      Path.of("target", "routing-dispatch-benchmark").toAbsolutePath();
  private static final UUID STORE = UUID.fromString("20000000-0000-4000-8000-000000000001");
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private static final MutableClock CLOCK = new MutableClock();

  @Container
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres")) {
        @Override
        public String getHost() {
          if (Boolean.getBoolean("bora.benchmark.direct-docker-network")
              && getContainerId() != null) {
            return getContainerInfo()
                .getNetworkSettings()
                .getNetworks()
                .values()
                .iterator()
                .next()
                .getIpAddress();
          }
          return super.getHost();
        }

        @Override
        public Integer getMappedPort(int originalPort) {
          return Boolean.getBoolean("bora.benchmark.direct-docker-network")
              ? originalPort
              : super.getMappedPort(originalPort);
        }
      };

  private ConfigurableApplicationContext left;
  private ConfigurableApplicationContext right;
  private JdbcClient jdbc;
  private TransactionTemplate setup;
  private ExecutorService executor;
  private final JsonMapper json = JsonMapper.builder().build();
  private final Map<String, Object> report = new LinkedHashMap<>();
  private final Map<String, Map<String, Object>> families = new LinkedHashMap<>();
  private final Map<String, Integer> variants = new LinkedHashMap<>();
  private int executed;
  private int verified;
  private int invariantChecks;

  @Test
  @Timeout(1_800)
  void verifiesTenThousandRealConcurrentRoundsAndDeadlineRecoveryAfterRestart() throws Exception {
    Instant started = Instant.now();
    report.put("startedAt", started.toString());
    report.put("sourceSha256", System.getProperty("bora.benchmark.source-sha256", "not-recorded"));
    report.put("javaVersion", Runtime.version().toString());
    report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
    report.put("routingManifest", MANIFEST);
    report.put(
        "method",
        "Dois contextos Spring e pools independentes; barreira antes de cada par de operações reais de serviço; sem HTTP ou mocks de roteamento/banco.");
    report.put(
        "fixture",
        "Pedidos e entregadores públicos simulados novos em cada rodada, na Paulista; dados terminais são preservados e retirados da fila após a verificação.");
    report.put("families", families);
    report.put("variants", variants);
    report.put("success", false);
    try {
      RoutingTools.prepareGraph(MANIFEST, GRAPHS);
      try (var input = getClass().getClassLoader().getResourceAsStream(MANIFEST)) {
        report.put("datasetSha256", json.readTree(input).path("sha256").asString());
      }
      left = start("left", false);
      right = start("right", false);
      assertThat(left).isNotSameAs(right);
      assertThat(left.getBean(DispatchCoordinator.class))
          .isNotSameAs(right.getBean(DispatchCoordinator.class));
      HikariDataSource firstPool = left.getBean(DataSource.class).unwrap(HikariDataSource.class);
      HikariDataSource secondPool = right.getBean(DataSource.class).unwrap(HikariDataSource.class);
      assertThat(firstPool).isNotSameAs(secondPool);
      report.put("pools", List.of(firstPool.getPoolName(), secondPool.getPoolName()));
      assertThat(firstPool.getPoolName()).isNotEqualTo(secondPool.getPoolName());
      jdbc = left.getBean(JdbcClient.class);
      setup = new TransactionTemplate(left.getBean(PlatformTransactionManager.class));
      report.put(
          "backendPids",
          List.of(
              jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single(),
              right
                  .getBean(JdbcClient.class)
                  .sql("SELECT pg_backend_pid()")
                  .query(Integer.class)
                  .single()));
      try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
        executor = threads;
        reservationRounds();
        doubleAcceptRounds();
        decisionRounds();
        pickupReassignmentRounds();
      }
      assertThat(executed).isEqualTo(10_000);
      assertThat(verified).isEqualTo(10_000);
      verifyGlobalInvariants();
      restartDeadlineProof();
      report.put("success", true);
    } catch (Throwable failure) {
      report.put("failureType", failure.getClass().getName());
      report.put(
          "failureMessage",
          String.valueOf(failure.getMessage())
              .substring(0, Math.min(1_000, String.valueOf(failure.getMessage()).length())));
      throw failure;
    } finally {
      report.put("finishedAt", Instant.now().toString());
      report.put("elapsedMillis", Duration.between(started, Instant.now()).toMillis());
      report.put("requestedRounds", 10_000);
      report.put("executedRounds", executed);
      report.put("verifiedRounds", verified);
      report.put("invariantChecks", invariantChecks);
      writeReport();
      if (right != null) right.close();
      if (left != null) left.close();
    }
  }

  private ConfigurableApplicationContext start(String name, boolean restart) {
    return new SpringApplicationBuilder(BoraApplication.class, Dependencies.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.application.name=bora-dispatch-benchmark-" + name,
            "--spring.datasource.url=" + database.getJdbcUrl(),
            "--spring.datasource.username=" + database.getUsername(),
            "--spring.datasource.password=" + database.getPassword(),
            "--bora.routing.graph-root=" + GRAPHS,
            "--bora.routing.manifest=" + MANIFEST,
            "--bora.dispatch.worker-enabled=" + restart,
            "--bora.dispatch.expiry-enabled=" + restart,
            "--bora.dispatch.require-activation=" + restart,
            "--bora.fulfillment.reconciliation-enabled=false",
            "--bora.couriers.flush-initial-delay-ms=3600000");
  }

  private void reservationRounds() throws Exception {
    String family = "reservation";
    begin(family);
    for (int round = 0; round < ROUNDS_PER_FAMILY; round++) {
      boolean sameOrder = round % 2 == 0;
      Fixture fixture = fixture(sameOrder ? 1 : 2, sameOrder ? 3 : 1, Kind.SEARCHING);
      List<Outcome> outcomes =
          race(
              family,
              () -> left.getBean(DispatchCoordinator.class).dispatchAvailableOrders(),
              () -> right.getBean(DispatchCoordinator.class).dispatchAvailableOrders());
      assertThat(outcomes).allMatch(Outcome::successful);
      assertThat(outcomes.stream().mapToInt(value -> ((Number) value.value()).intValue()).sum())
          .isEqualTo(1);
      assertThat(
              jdbc.sql(
                      "SELECT count(*) FROM offers WHERE order_id IN (:orders) AND status='PENDING'")
                  .param("orders", fixture.orders())
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
      passed(
          family, sameOrder ? "sameOrderMultipleCouriers" : "multipleOrdersSameCourier", fixture);
    }
    end(family);
  }

  private void doubleAcceptRounds() throws Exception {
    String family = "doubleAccept";
    begin(family);
    for (int round = 0; round < ROUNDS_PER_FAMILY; round++) {
      Fixture fixture = fixture(1, 1, Kind.PENDING);
      List<Outcome> outcomes =
          race(
              family,
              () -> {
                left.getBean(DispatchCoordinator.class).accept(fixture.courier(), fixture.offer());
                return null;
              },
              () -> {
                right.getBean(DispatchCoordinator.class).accept(fixture.courier(), fixture.offer());
                return null;
              });
      assertThat(outcomes.stream().filter(Outcome::successful).count()).isEqualTo(1);
      assertThat(orderStatus(fixture)).isEqualTo("ASSIGNED");
      assertThat(offerStatus(fixture)).isEqualTo("ACCEPTED");
      assertThat(events(fixture, "ORDER_ASSIGNED")).isEqualTo(1);
      assertThat(events(fixture, "OFFER_ACCEPTED")).isEqualTo(1);
      passed(family, "sameOfferDoubleAccept", fixture);
    }
    end(family);
  }

  private void decisionRounds() throws Exception {
    String family = "decision";
    begin(family);
    for (int round = 0; round < ROUNDS_PER_FAMILY; round++) {
      Fixture fixture = fixture(1, 1, Kind.PENDING);
      int decision = round % 3;
      if (decision == 1) CLOCK.at.set(NOW.plusSeconds(31));
      List<Outcome> outcomes =
          race(
              family,
              () -> {
                left.getBean(DispatchCoordinator.class).accept(fixture.courier(), fixture.offer());
                return null;
              },
              () -> {
                if (decision == 0)
                  right
                      .getBean(DispatchCoordinator.class)
                      .reject(fixture.courier(), fixture.offer());
                else if (decision == 1) right.getBean(PendingOfferCleanup.class).expireDueOffers();
                else
                  right
                      .getBean(OrderLifecycleService.class)
                      .cancelByOperations(fixture.order(), 0, "benchmark:operations");
                return null;
              });
      if (decision == 1) {
        assertThat(offerStatus(fixture)).isEqualTo("EXPIRED");
        assertThat(orderStatus(fixture)).isEqualTo("SEARCHING");
        assertThat(events(fixture, "ORDER_ASSIGNED")).isZero();
        assertThat(
                jdbc.sql("SELECT responded_at>=expires_at FROM offers WHERE id=:id")
                    .param("id", fixture.offer())
                    .query(Boolean.class)
                    .single())
            .isTrue();
      } else {
        assertThat(outcomes.stream().filter(Outcome::successful).count()).isEqualTo(1);
        assertThat(offerStatus(fixture))
            .isIn(
                decision == 0 ? List.of("ACCEPTED", "REJECTED") : List.of("ACCEPTED", "CANCELED"));
        assertThat(events(fixture, "ORDER_ASSIGNED")).isLessThanOrEqualTo(1);
      }
      passed(
          family,
          decision == 0 ? "acceptReject" : decision == 1 ? "acceptExpire" : "acceptCancel",
          fixture);
      CLOCK.at.set(NOW);
    }
    end(family);
  }

  private void pickupReassignmentRounds() throws Exception {
    String family = "pickupReassignment";
    begin(family);
    for (int round = 0; round < ROUNDS_PER_FAMILY; round++) {
      Fixture fixture = fixture(1, 1, Kind.AT_STORE);
      List<Outcome> outcomes =
          race(
              family,
              () ->
                  left.getBean(FulfillmentCoordinator.class)
                      .pickUp(fixture.courier(), fixture.order(), 0),
              () -> {
                right
                    .getBean(DispatchCoordinator.class)
                    .reassign(fixture.operations(), fixture.order(), 0);
                return null;
              },
              true);
      assertThat(outcomes.stream().filter(Outcome::successful).count()).isEqualTo(1);
      assertThat(orderStatus(fixture)).isIn("SEARCHING", "PICKED_UP");
      assertThat(events(fixture, "ORDER_REASSIGNED") + events(fixture, "ORDER_PICKED_UP"))
          .isEqualTo(1);
      if ("ORDER_NOT_FOUND".equals(outcomes.getFirst().conflict())) {
        assertThat(orderStatus(fixture)).isEqualTo("SEARCHING");
        assertThat(events(fixture, "ORDER_REASSIGNED")).isEqualTo(1);
        assertThat(events(fixture, "ORDER_PICKED_UP")).isZero();
        assertThat(
                jdbc.sql("SELECT courier_id IS NULL AND version=1 FROM orders WHERE id=:id")
                    .param("id", fixture.order())
                    .query(Boolean.class)
                    .single())
            .isTrue();
      }
      passed(family, "pickupOperationsReassignment", fixture);
    }
    end(family);
  }

  private List<Outcome> race(String family, Callable<?> first, Callable<?> second)
      throws Exception {
    return race(family, first, second, false);
  }

  private List<Outcome> race(
      String family, Callable<?> first, Callable<?> second, boolean firstMayLosePickupOwnership)
      throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(3);
    var leftResult =
        executor.submit(
            () -> {
              barrier.await(10, TimeUnit.SECONDS);
              return call(first, firstMayLosePickupOwnership);
            });
    var rightResult =
        executor.submit(
            () -> {
              barrier.await(10, TimeUnit.SECONDS);
              return call(second, false);
            });
    barrier.await(10, TimeUnit.SECONDS);
    executed++;
    increment(families.get(family), "executedRounds");
    return List.of(leftResult.get(30, TimeUnit.SECONDS), rightResult.get(30, TimeUnit.SECONDS));
  }

  private Outcome call(Callable<?> action, boolean mayLosePickupOwnership) throws Exception {
    try {
      return new Outcome(true, action.call(), null);
    } catch (ApiException conflict) {
      if (mayLosePickupOwnership
          && conflict.status().value() == 404
          && "ORDER_NOT_FOUND".equals(conflict.code())) {
        return new Outcome(false, null, conflict.code());
      }
      if (conflict.status().value() != 409) throw conflict;
      return new Outcome(false, null, conflict.code());
    } catch (OrderVersionConflict | InvalidOrderTransition conflict) {
      return new Outcome(false, null, conflict.getClass().getSimpleName());
    }
  }

  private Fixture fixture(int orders, int couriers, Kind kind) {
    CLOCK.at.set(NOW);
    List<UUID> orderIds = new ArrayList<>();
    List<UUID> courierIds = new ArrayList<>();
    for (int index = 0; index < orders; index++) orderIds.add(UUID.randomUUID());
    for (int index = 0; index < couriers; index++) courierIds.add(UUID.randomUUID());
    UUID offer = kind == Kind.PENDING ? UUID.randomUUID() : null;
    setup.executeWithoutResult(
        status -> {
          for (UUID courier : courierIds) {
            jdbc.sql(
                    """
            INSERT INTO couriers(id,name,status,location,location_updated_at,simulated)
            VALUES(:id,'Entregador de concorrência',:status,
              ST_SetSRID(ST_MakePoint(-46.6559,-23.5614),4326)::geography,:now,true)
            """)
                .param("id", courier)
                .param("now", timestamp(NOW))
                .param(
                    "status",
                    kind == Kind.PENDING ? "OFFERED" : kind == Kind.AT_STORE ? "BUSY" : "AVAILABLE")
                .update();
          }
          for (UUID order : orderIds) {
            jdbc.sql(
                    """
            INSERT INTO orders(id,public_code,store_id,dropoff,status,courier_id,sla_deadline,
              next_attempt_at,created_at,assigned_at,at_store_at)
            VALUES(:id,:code,:store,ST_SetSRID(ST_MakePoint(-46.6558,-23.56135),4326)::geography,
              :status,:courier,:sla,:attempt,:created,:assigned,:atStore)
            """)
                .param("id", order)
                .param("code", UUID.randomUUID().toString())
                .param("store", STORE)
                .param("status", kind == Kind.AT_STORE ? "AT_STORE" : "SEARCHING")
                .param("courier", kind == Kind.AT_STORE ? courierIds.getFirst() : null)
                .param("sla", timestamp(NOW.plusSeconds(1800)))
                .param("attempt", kind == Kind.AT_STORE ? null : timestamp(NOW))
                .param("created", timestamp(NOW.minusSeconds(10)))
                .param("assigned", kind == Kind.AT_STORE ? timestamp(NOW.minusSeconds(5)) : null)
                .param("atStore", kind == Kind.AT_STORE ? timestamp(NOW.minusSeconds(2)) : null)
                .update();
          }
          if (offer != null) {
            jdbc.sql(
                    """
            INSERT INTO offers(id,order_id,courier_id,status,eta_to_pickup_s,created_at,expires_at)
            VALUES(:id,:order,:courier,'PENDING',10,:created,:expires)
            """)
                .param("id", offer)
                .param("order", orderIds.getFirst())
                .param("courier", courierIds.getFirst())
                .param("created", timestamp(NOW))
                .param("expires", timestamp(NOW.plusSeconds(30)))
                .update();
          }
        });
    return new Fixture(
        orderIds,
        courierIds,
        offer,
        new DemoPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            DemoRole.COURIER,
            null,
            courierIds.getFirst(),
            false),
        new DemoPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), DemoRole.OPERATIONS, null, null, false));
  }

  private void passed(String family, String variant, Fixture fixture) throws Exception {
    verifyRoundInvariants(fixture);
    verified++;
    increment(families.get(family), "verifiedRounds");
    variants.merge(variant, 1, Integer::sum);
    retire(fixture);
    if (verified % 250 == 0) {
      report.put("executedRounds", executed);
      report.put("verifiedRounds", verified);
      writeReport();
      System.out.println("DISPATCH_CONCURRENCY_PROGRESS " + verified + "/10000");
    }
  }

  private void verifyRoundInvariants(Fixture fixture) {
    Long violations =
        jdbc.sql(
                """
        SELECT
          (SELECT count(*) FROM (SELECT order_id FROM offers WHERE order_id IN (:orders)
            AND status='PENDING' GROUP BY order_id HAVING count(*)>1) x)
        + (SELECT count(*) FROM (SELECT courier_id FROM offers WHERE courier_id IN (:couriers)
            AND status='PENDING' GROUP BY courier_id HAVING count(*)>1) x)
        + (SELECT count(*) FROM (SELECT courier_id FROM orders WHERE courier_id IN (:couriers)
            AND status IN ('ASSIGNED','AT_STORE','PICKED_UP') GROUP BY courier_id HAVING count(*)>1) x)
        + (SELECT count(*) FROM offers f JOIN orders o ON o.id=f.order_id JOIN couriers c ON c.id=f.courier_id
            WHERE f.order_id IN (:orders) AND f.status='PENDING' AND (o.status<>'SEARCHING' OR c.status<>'OFFERED'))
        + (SELECT count(*) FROM orders o JOIN couriers c ON c.id=o.courier_id WHERE o.id IN (:orders)
            AND o.status IN ('ASSIGNED','AT_STORE','PICKED_UP') AND c.status<>'BUSY')
        + (SELECT count(*) FROM (SELECT order_id FROM order_events WHERE order_id IN (:orders)
            AND type='ORDER_ASSIGNED' GROUP BY order_id HAVING count(*)>1) x)
        """)
            .param("orders", fixture.orders())
            .param("couriers", fixture.couriers())
            .query(Long.class)
            .single();
    invariantChecks++;
    assertThat(violations).as("ofertas, atribuições e recursos da rodada %s", executed).isZero();
  }

  private void retire(Fixture fixture) {
    setup.executeWithoutResult(
        status -> {
          jdbc.sql(
                  "UPDATE offers SET status='CANCELED',responded_at=GREATEST(created_at,:now) WHERE order_id IN (:orders) AND status='PENDING'")
              .param("orders", fixture.orders())
              .param("now", timestamp(CLOCK.instant()))
              .update();
          jdbc.sql(
                  """
          UPDATE orders SET status=CASE WHEN status='PICKED_UP' THEN 'DELIVERED' ELSE 'CANCELED' END,
            delivered_at=CASE WHEN status='PICKED_UP' THEN :now ELSE NULL END,
            canceled_at=CASE WHEN status='PICKED_UP' THEN NULL ELSE :now END,next_attempt_at=NULL
          WHERE id IN (:orders)
          """)
              .param("orders", fixture.orders())
              .param("now", timestamp(CLOCK.instant()))
              .update();
          jdbc.sql(
                  "UPDATE couriers SET status='OFFLINE',position_epoch=position_epoch+1 WHERE id IN (:couriers)")
              .param("couriers", fixture.couriers())
              .update();
        });
  }

  private void verifyGlobalInvariants() {
    Map<String, Long> invariants = new LinkedHashMap<>();
    invariants.put(
        "ordersWithMultiplePendingOffers",
        jdbc.sql(
                "SELECT count(*) FROM (SELECT order_id FROM offers WHERE status='PENDING' GROUP BY order_id HAVING count(*)>1) x")
            .query(Long.class)
            .single());
    invariants.put(
        "couriersWithMultiplePendingOffers",
        jdbc.sql(
                "SELECT count(*) FROM (SELECT courier_id FROM offers WHERE status='PENDING' GROUP BY courier_id HAVING count(*)>1) x")
            .query(Long.class)
            .single());
    invariants.put(
        "couriersWithMultipleActiveOrders",
        jdbc.sql(
                "SELECT count(*) FROM (SELECT courier_id FROM orders WHERE status IN ('ASSIGNED','AT_STORE','PICKED_UP') GROUP BY courier_id HAVING count(*)>1) x")
            .query(Long.class)
            .single());
    invariants.put(
        "ordersWithMultipleAssignmentEvents",
        jdbc.sql(
                "SELECT count(*) FROM (SELECT order_id FROM order_events WHERE type='ORDER_ASSIGNED' GROUP BY order_id HAVING count(*)>1) x")
            .query(Long.class)
            .single());
    invariants.put(
        "invalidDurableOfferDeadlines",
        jdbc.sql(
                "SELECT count(*) FROM offers WHERE expires_at<=created_at OR (status IN ('ACCEPTED','REJECTED') AND responded_at>=expires_at) OR (status='EXPIRED' AND responded_at<expires_at)")
            .query(Long.class)
            .single());
    report.put("globalInvariantViolations", invariants);
    assertThat(invariants.values()).allMatch(value -> value == 0);
  }

  private void restartDeadlineProof() throws Exception {
    Fixture fixture = fixture(1, 1, Kind.SEARCHING);
    assertThat(left.getBean(DispatchCoordinator.class).dispatchAvailableOrders()).isEqualTo(1);
    UUID offer =
        jdbc.sql("SELECT id FROM offers WHERE order_id=:order AND status='PENDING'")
            .param("order", fixture.order())
            .query(UUID.class)
            .single();
    Instant deadline =
        jdbc.sql("SELECT expires_at FROM offers WHERE id=:id")
            .param("id", offer)
            .query(OffsetDateTime.class)
            .single()
            .toInstant();
    assertThat(deadline).isEqualTo(NOW.plusSeconds(30));
    Object oldCoordinator = left.getBean(DispatchCoordinator.class);
    right.close();
    right = null;
    left.close();
    left = null;
    CLOCK.at.set(deadline.plusSeconds(1));
    left = start("restarted", true);
    jdbc = left.getBean(JdbcClient.class);
    assertThat(left.getBean(DispatchCoordinator.class)).isNotSameAs(oldCoordinator);
    assertThat(left.getBean(DispatchActivation.class).allowsCreation()).isFalse();
    long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!jdbc.sql("SELECT status='EXPIRED' FROM offers WHERE id=:id")
            .param("id", offer)
            .query(Boolean.class)
            .single()
        && System.nanoTime() < waitUntil) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
    assertThat(
            jdbc.sql("SELECT status FROM offers WHERE id=:id")
                .param("id", offer)
                .query(String.class)
                .single())
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.sql("SELECT expires_at FROM offers WHERE id=:id")
                .param("id", offer)
                .query(OffsetDateTime.class)
                .single()
                .toInstant())
        .isEqualTo(deadline);
    assertThat(
            jdbc.sql("SELECT status FROM couriers WHERE id=:id")
                .param("id", fixture.couriers().getFirst())
                .query(String.class)
                .single())
        .isEqualTo("AVAILABLE");
    assertThat(
            jdbc.sql("SELECT count(*) FROM offers WHERE order_id=:id AND status='PENDING'")
                .param("id", fixture.order())
                .query(Long.class)
                .single())
        .isZero();
    report.put(
        "restartDeadlineProof",
        Map.of(
            "restartedSpringContext",
            true,
            "bothOriginalContextsClosed",
            true,
            "creationGateClosed",
            true,
            "passiveExpiryEnabled",
            true,
            "persistedDeadline",
            deadline.toString(),
            "clockAfterRestart",
            CLOCK.instant().toString(),
            "offerStatus",
            "EXPIRED",
            "courierStatus",
            "AVAILABLE"));
  }

  private String orderStatus(Fixture fixture) {
    return jdbc.sql("SELECT status FROM orders WHERE id=:id")
        .param("id", fixture.order())
        .query(String.class)
        .single();
  }

  private String offerStatus(Fixture fixture) {
    return jdbc.sql("SELECT status FROM offers WHERE id=:id")
        .param("id", fixture.offer())
        .query(String.class)
        .single();
  }

  private long events(Fixture fixture, String type) {
    return jdbc.sql("SELECT count(*) FROM order_events WHERE order_id=:id AND type=:type")
        .param("id", fixture.order())
        .param("type", type)
        .query(Long.class)
        .single();
  }

  private void begin(String family) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("requestedRounds", ROUNDS_PER_FAMILY);
    values.put("executedRounds", 0);
    values.put("verifiedRounds", 0);
    values.put("startedAt", Instant.now().toString());
    families.put(family, values);
  }

  private void end(String family) throws Exception {
    families.get(family).put("finishedAt", Instant.now().toString());
    assertThat(families.get(family).get("verifiedRounds")).isEqualTo(ROUNDS_PER_FAMILY);
    writeReport();
  }

  private static void increment(Map<String, Object> values, String key) {
    values.put(key, ((Number) values.get(key)).intValue() + 1);
  }

  private void writeReport() throws Exception {
    Files.createDirectories(Path.of("target"));
    Files.writeString(
        Path.of("target", "dispatch-concurrency-benchmark.json"),
        json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private enum Kind {
    SEARCHING,
    PENDING,
    AT_STORE
  }

  private record Outcome(boolean successful, Object value, String conflict) {}

  private record Fixture(
      List<UUID> orders,
      List<UUID> couriers,
      UUID offer,
      DemoPrincipal courier,
      DemoPrincipal operations) {
    UUID order() {
      return orders.getFirst();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    @Primary
    Clock benchmarkClock() {
      return CLOCK;
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> at = new AtomicReference<>(NOW);

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
