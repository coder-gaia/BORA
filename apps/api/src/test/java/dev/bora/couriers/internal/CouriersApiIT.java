package dev.bora.couriers.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import dev.bora.BoraApplication;
import dev.bora.couriers.CourierCommand;
import dev.bora.couriers.CourierDirectory;
import dev.bora.couriers.CourierPositionsPersisted;
import dev.bora.couriers.PositionSample;
import dev.bora.demo.DemoLimits;
import dev.bora.routing.RoutingTools;
import dev.bora.shared.ApiException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CouriersApiIT.ObservationConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouriersApiIT {
  private static final String MANIFEST = "routing/sp-fixture-manifest.json";
  private static final Path GRAPHS = Path.of("target", "routing-couriers-it").toAbsolutePath();

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    RoutingTools.prepareGraph(MANIFEST, GRAPHS);
    registry.add("bora.routing.graph-root", GRAPHS::toString);
    registry.add("bora.routing.manifest", () -> MANIFEST);
    registry.add("bora.couriers.flush-initial-delay-ms", () -> "3600000");
    registry.add("server.servlet.session.cookie.secure", () -> "false");
  }

  @MockitoSpyBean JdbcClient jdbc;
  @Autowired CourierPositionBuffer buffer;
  @Autowired CourierDirectory directory;
  @Autowired DemoLimits limits;
  @Autowired Observation observation;
  @Autowired PlatformTransactionManager transactions;
  @LocalServerPort int port;
  private ConfigurableApplicationContext second;
  private int secondPort;
  private CourierPositionBuffer secondBuffer;

  @BeforeAll
  void startSecond() {
    second =
        new SpringApplicationBuilder(BoraApplication.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--bora.routing.graph-root=" + GRAPHS,
                "--bora.routing.manifest=" + MANIFEST,
                "--bora.couriers.flush-initial-delay-ms=3600000",
                "--server.servlet.session.cookie.secure=false");
    secondPort = ((WebServerApplicationContext) second).getWebServer().getPort();
    secondBuffer = second.getBean(CourierPositionBuffer.class);
  }

  @AfterAll
  void closeSecond() {
    if (second != null) second.close();
  }

  @BeforeEach
  void clear() {
    reset(jdbc);
    observation.fail.set(false);
    buffer.flush();
    secondBuffer.flush();
    observation.events.clear();
    jdbc.sql("DELETE FROM rate_limit_buckets").update();
  }

  @Test
  void changesOwnAvailabilityAndBlocksWrongRolesAndActiveWork() throws Exception {
    try (var browser = new CourierHttpFixture()) {
      assertThat(browser.login(port, "STORE").statusCode()).isEqualTo(200);
      assertThat(browser.get(port, "/courier/me").statusCode()).isEqualTo(403);
      assertThat(browser.login(port, "COURIER").statusCode()).isEqualTo(200);
      var initial = browser.get(port, "/courier/me");
      assertThat(CourierHttpFixture.json(initial).path("status").asString()).isEqualTo("OFFLINE");
      assertThat(CourierHttpFixture.json(initial).path("location").isNull()).isTrue();
      assertThat(browser.post(port, "/courier/availability", "{\"online\":true}").statusCode())
          .isEqualTo(400);
      var online = browser.online(port);
      assertThat(online.statusCode()).isEqualTo(200);
      UUID courier = UUID.fromString(CourierHttpFixture.json(online).path("id").asString());
      assertThat(CourierHttpFixture.json(online).path("status").asString()).isEqualTo("AVAILABLE");
      assertThat(CourierHttpFixture.json(browser.online(secondPort)).path("version").asLong())
          .isEqualTo(1);
      var transaction = new TransactionTemplate(transactions);
      transaction.executeWithoutResult(
          status -> directory.transition(courier, new CourierCommand.ReceiveOffer()));
      assertThat(browser.post(port, "/courier/availability", "{\"online\":false}").statusCode())
          .isEqualTo(409);
      transaction.executeWithoutResult(
          status -> directory.transition(courier, new CourierCommand.CancelOffer()));
      transaction.executeWithoutResult(
          status -> directory.transition(courier, new CourierCommand.CancelOrder()));
      assertThat(browser.post(port, "/courier/availability", "{\"online\":false}").statusCode())
          .isEqualTo(200);
      assertThat(
              browser
                  .positions(
                      port, List.of(new PositionSample(Instant.now(), CourierHttpFixture.PICKUP)))
                  .statusCode())
          .isEqualTo(409);
    }
  }

  @Test
  void keepsReceiptSeparateFromDurabilityAndPublishesAllPointsAfterCommit() throws Exception {
    try (var browser = online()) {
      Instant before = CourierHttpFixture.recorded(browser.get(port, "/courier/me"));
      Instant first = Instant.now().plusMillis(200).truncatedTo(ChronoUnit.MICROS);
      var samples =
          List.of(
              new PositionSample(first, CourierHttpFixture.PICKUP),
              new PositionSample(first.plusMillis(100), CourierHttpFixture.NEARBY));
      var receipt = browser.positions(port, samples);
      assertThat(receipt.statusCode()).isEqualTo(202);
      assertThat(CourierHttpFixture.json(receipt).path("accepted").asInt()).isEqualTo(2);
      assertThat(CourierHttpFixture.recorded(browser.get(secondPort, "/courier/me")))
          .isEqualTo(before);
      buffer.flush();
      assertThat(CourierHttpFixture.recorded(browser.get(secondPort, "/courier/me")))
          .isEqualTo(first.plusMillis(100));
      assertThat(observation.events).hasSize(1);
      var event = observation.events.getFirst();
      assertThat(event.positions().values().iterator().next()).containsExactlyElementsOf(samples);
      assertThatThrownBy(() -> event.positions().clear())
          .isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> event.positions().values().iterator().next().clear())
          .isInstanceOf(UnsupportedOperationException.class);
      assertThat(CourierHttpFixture.json(browser.positions(port, samples)).path("ignored").asInt())
          .isEqualTo(2);
      assertThat(buffer.metrics().bufferedPoints()).isZero();
    }
  }

  @Test
  void preventsOlderReplicaFlushFromReplacingNewerSharedPosition() throws Exception {
    try (var browser = online()) {
      Instant first = Instant.now().plusMillis(100).truncatedTo(ChronoUnit.MICROS);
      Instant later = first.plusMillis(100);
      assertThat(
              browser
                  .positions(port, List.of(new PositionSample(first, CourierHttpFixture.PICKUP)))
                  .statusCode())
          .isEqualTo(202);
      assertThat(
              browser
                  .positions(
                      secondPort, List.of(new PositionSample(later, CourierHttpFixture.NEARBY)))
                  .statusCode())
          .isEqualTo(202);
      secondBuffer.flush();
      buffer.flush();
      assertThat(CourierHttpFixture.recorded(browser.get(port, "/courier/me"))).isEqualTo(later);
      assertThat(observation.events).isEmpty();
    }
  }

  @Test
  void preservesFutureRecordedTimeAndRejectsQueuedPositionsFromAnEarlierOnlineEpoch()
      throws Exception {
    try (var browser = online()) {
      Instant future = Instant.now().plusSeconds(3).truncatedTo(ChronoUnit.MICROS);
      assertThat(
              browser
                  .positions(port, List.of(new PositionSample(future, CourierHttpFixture.NEARBY)))
                  .statusCode())
          .isEqualTo(202);
      buffer.flush();
      assertThat(CourierHttpFixture.recorded(browser.online(secondPort))).isEqualTo(future);
      Instant queued = future.plusMillis(100);
      assertThat(
              browser
                  .positions(
                      secondPort, List.of(new PositionSample(queued, CourierHttpFixture.NEARBY)))
                  .statusCode())
          .isEqualTo(202);
      assertThat(browser.post(port, "/courier/availability", "{\"online\":false}").statusCode())
          .isEqualTo(200);
      var online = browser.online(port);
      Instant newEpoch = CourierHttpFixture.recorded(online);
      assertThat(newEpoch).isAfter(future);
      secondBuffer.flush();
      var snapshot = browser.get(secondPort, "/courier/me");
      assertThat(CourierHttpFixture.recorded(snapshot)).isEqualTo(newEpoch);
      assertThat(CourierHttpFixture.json(snapshot).path("location").path("latitude").asDouble())
          .isEqualTo(CourierHttpFixture.PICKUP.latitude());
    }
  }

  @Test
  void fencesQueuedFuturePointsAcrossReservationsAssignmentsAndReleases() throws Exception {
    try (var browser = online()) {
      UUID courier =
          UUID.fromString(
              CourierHttpFixture.json(browser.get(port, "/courier/me")).path("id").asString());
      Instant before = CourierHttpFixture.recorded(browser.get(port, "/courier/me"));
      long epoch = epoch(courier);
      var future =
          new PositionSample(
              Instant.now().plusSeconds(4).truncatedTo(ChronoUnit.MICROS),
              CourierHttpFixture.NEARBY);
      assertThat(browser.positions(secondPort, List.of(future)).statusCode()).isEqualTo(202);
      var transaction = new TransactionTemplate(transactions);
      transaction.executeWithoutResult(
          status -> {
            directory.transition(courier, new CourierCommand.ReceiveOffer());
            directory.transition(courier, new CourierCommand.AcceptOffer());
            directory.transition(courier, new CourierCommand.ReassignOrder());
            directory.transition(courier, new CourierCommand.ReceiveOffer());
            directory.transition(courier, new CourierCommand.AcceptOffer());
          });
      assertThat(epoch(courier)).isEqualTo(epoch + 5);
      secondBuffer.flush();
      assertThat(CourierHttpFixture.recorded(browser.get(port, "/courier/me"))).isEqualTo(before);
      assertThat(observation.events).isEmpty();
      assertThat(browser.positions(port, List.of(future)).statusCode()).isEqualTo(202);
      buffer.flush();
      assertThat(CourierHttpFixture.recorded(browser.get(secondPort, "/courier/me")))
          .isEqualTo(future.recordedAt());
      assertThat(observation.events.getLast().epochs().get(courier)).isEqualTo(epoch + 5);
    }
  }

  @Test
  void incrementsEpochForEveryActualReleaseAndKeepsRepeatedReleasesIdempotent() throws Exception {
    try (var browser = online()) {
      UUID courier =
          UUID.fromString(
              CourierHttpFixture.json(browser.get(port, "/courier/me")).path("id").asString());
      var transaction = new TransactionTemplate(transactions);
      for (CourierCommand release :
          List.of(
              new CourierCommand.RejectOffer(),
              new CourierCommand.ExpireOffer(),
              new CourierCommand.CancelOffer(),
              new CourierCommand.CompleteDelivery(),
              new CourierCommand.CancelOrder(),
              new CourierCommand.ReassignOrder())) {
        boolean assigned =
            release instanceof CourierCommand.CompleteDelivery
                || release instanceof CourierCommand.CancelOrder
                || release instanceof CourierCommand.ReassignOrder;
        long before = epoch(courier);
        transaction.executeWithoutResult(
            status -> {
              directory.transition(courier, new CourierCommand.ReceiveOffer());
              if (assigned) directory.transition(courier, new CourierCommand.AcceptOffer());
              directory.transition(courier, release);
            });
        long after = epoch(courier);
        assertThat(after).isEqualTo(before + (assigned ? 3 : 2));
        long version = directory.find(courier).version();
        transaction.executeWithoutResult(status -> directory.transition(courier, release));
        assertThat(epoch(courier)).isEqualTo(after);
        assertThat(directory.find(courier).version()).isEqualTo(version);
      }
    }
  }

  private long epoch(UUID courier) {
    return jdbc.sql("SELECT position_epoch FROM couriers WHERE id=:id")
        .param("id", courier)
        .query(Long.class)
        .single();
  }

  @Test
  void bulkFlushFiltersStaleAndEarlierEpochRowsWithoutLosingValidCourierSamples() throws Exception {
    try (var valid = online();
        var disabled = online();
        var stale = online()) {
      UUID validId =
          UUID.fromString(
              CourierHttpFixture.json(valid.get(port, "/courier/me")).path("id").asString());
      UUID disabledId =
          UUID.fromString(
              CourierHttpFixture.json(disabled.get(port, "/courier/me")).path("id").asString());
      UUID staleId =
          UUID.fromString(
              CourierHttpFixture.json(stale.get(port, "/courier/me")).path("id").asString());
      Instant disabledBefore = CourierHttpFixture.recorded(disabled.get(port, "/courier/me"));
      Instant first = Instant.now().plusMillis(100).truncatedTo(ChronoUnit.MICROS);
      var validSamples =
          List.of(
              new PositionSample(first, CourierHttpFixture.PICKUP),
              new PositionSample(first.plusMillis(100), CourierHttpFixture.NEARBY));
      assertThat(valid.positions(port, validSamples).statusCode()).isEqualTo(202);
      assertThat(
              disabled
                  .positions(port, List.of(new PositionSample(first, CourierHttpFixture.NEARBY)))
                  .statusCode())
          .isEqualTo(202);
      assertThat(
              stale
                  .positions(port, List.of(new PositionSample(first, CourierHttpFixture.PICKUP)))
                  .statusCode())
          .isEqualTo(202);
      assertThat(
              stale
                  .positions(
                      secondPort,
                      List.of(new PositionSample(first.plusMillis(200), CourierHttpFixture.NEARBY)))
                  .statusCode())
          .isEqualTo(202);
      assertThat(
              disabled.post(secondPort, "/courier/availability", "{\"online\":false}").statusCode())
          .isEqualTo(200);
      secondBuffer.flush();
      buffer.flush();
      var current = valid.get(secondPort, "/courier/me");
      assertThat(CourierHttpFixture.recorded(current)).isEqualTo(first.plusMillis(100));
      assertThat(CourierHttpFixture.json(current).path("version").asLong()).isEqualTo(1);
      assertThat(CourierHttpFixture.recorded(disabled.get(port, "/courier/me")))
          .isEqualTo(disabledBefore);
      assertThat(
              CourierHttpFixture.json(disabled.get(port, "/courier/me")).path("status").asString())
          .isEqualTo("OFFLINE");
      assertThat(CourierHttpFixture.recorded(stale.get(port, "/courier/me")))
          .isEqualTo(first.plusMillis(200));
      assertThat(observation.events).hasSize(1);
      var event = observation.events.getFirst();
      assertThat(event.positions())
          .containsOnlyKeys(validId)
          .doesNotContainKeys(disabledId, staleId);
      assertThat(event.positions().get(validId)).containsExactlyElementsOf(validSamples);
      assertThat(event.epochs()).containsOnlyKeys(validId);
      assertThat(buffer.metrics().bufferedPoints()).isZero();
    }
  }

  @Test
  void bulkFlushRollsBackEveryCourierWhenOneUpdateFailsAndRetriesTheEntireBatch() throws Exception {
    try (var first = online();
        var secondCourier = online()) {
      UUID firstId =
          UUID.fromString(
              CourierHttpFixture.json(first.get(port, "/courier/me")).path("id").asString());
      UUID secondId =
          UUID.fromString(
              CourierHttpFixture.json(secondCourier.get(port, "/courier/me"))
                  .path("id")
                  .asString());
      Instant firstBefore = CourierHttpFixture.recorded(first.get(port, "/courier/me"));
      Instant secondBefore = CourierHttpFixture.recorded(secondCourier.get(port, "/courier/me"));
      Instant recorded = Instant.now().plusMillis(100).truncatedTo(ChronoUnit.MICROS);
      var points =
          List.of(
              new PositionSample(recorded, CourierHttpFixture.PICKUP),
              new PositionSample(recorded.plusMillis(100), CourierHttpFixture.NEARBY));
      assertThat(first.positions(port, points).statusCode()).isEqualTo(202);
      assertThat(secondCourier.positions(port, points).statusCode()).isEqualTo(202);
      jdbc.sql(
              """
          CREATE FUNCTION reject_bulk_courier_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
          IF NEW.id='%s'::uuid THEN RAISE EXCEPTION 'Falha controlada do lote'; END IF;
          RETURN NEW; END; $$
          """
                  .formatted(secondId))
          .update();
      jdbc.sql(
              "CREATE TRIGGER reject_bulk_courier BEFORE UPDATE ON couriers FOR EACH ROW EXECUTE FUNCTION reject_bulk_courier_update()")
          .update();
      try {
        long failures = buffer.metrics().flushFailures();
        buffer.flush();
        assertThat(buffer.metrics().flushFailures()).isEqualTo(failures + 1);
        assertThat(buffer.metrics().bufferedPoints()).isEqualTo(4);
        assertThat(observation.events).isEmpty();
        assertThat(CourierHttpFixture.recorded(first.get(secondPort, "/courier/me")))
            .isEqualTo(firstBefore);
        assertThat(CourierHttpFixture.recorded(secondCourier.get(secondPort, "/courier/me")))
            .isEqualTo(secondBefore);
      } finally {
        jdbc.sql("DROP TRIGGER reject_bulk_courier ON couriers").update();
        jdbc.sql("DROP FUNCTION reject_bulk_courier_update()").update();
      }
      buffer.flush();
      assertThat(CourierHttpFixture.recorded(first.get(secondPort, "/courier/me")))
          .isEqualTo(recorded.plusMillis(100));
      assertThat(CourierHttpFixture.recorded(secondCourier.get(secondPort, "/courier/me")))
          .isEqualTo(recorded.plusMillis(100));
      assertThat(observation.events).hasSize(1);
      var event = observation.events.getFirst();
      assertThat(event.positions()).containsOnlyKeys(firstId, secondId);
      assertThat(event.positions().get(firstId)).containsExactlyElementsOf(points);
      assertThat(event.positions().get(secondId)).containsExactlyElementsOf(points);
      assertThat(buffer.metrics().bufferedPoints()).isZero();
    }
  }

  @Test
  @Timeout(30)
  void mergesNewerArrivalsAfterTransactionalFlushRollback() throws Exception {
    try (var browser = online()) {
      UUID id =
          UUID.fromString(
              CourierHttpFixture.json(browser.get(port, "/courier/me")).path("id").asString());
      Instant first = Instant.now().plusMillis(100).truncatedTo(ChronoUnit.MICROS);
      browser.positions(port, List.of(new PositionSample(first, CourierHttpFixture.PICKUP)));
      jdbc.sql(
              "CREATE FUNCTION reject_courier_test_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='"
                  + id
                  + "'::uuid THEN RAISE EXCEPTION 'Falha controlada'; END IF; RETURN NEW; END; $$")
          .update();
      jdbc.sql(
              "CREATE TRIGGER reject_courier_test BEFORE UPDATE ON couriers FOR EACH ROW EXECUTE FUNCTION reject_courier_test_update()")
          .update();
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch continueFlush = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                entered.countDown();
                assertThat(continueFlush.await(10, TimeUnit.SECONDS)).isTrue();
                return invocation.callRealMethod();
              })
          .when(jdbc)
          .sql(startsWith("WITH previous"));
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var failed = executor.submit(buffer::flush);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(
                browser
                    .positions(
                        port,
                        List.of(
                            new PositionSample(first.plusMillis(100), CourierHttpFixture.NEARBY)))
                    .statusCode())
            .isEqualTo(202);
        continueFlush.countDown();
        failed.get(10, TimeUnit.SECONDS);
      } finally {
        continueFlush.countDown();
        reset(jdbc);
        jdbc.sql("DROP TRIGGER reject_courier_test ON couriers").update();
        jdbc.sql("DROP FUNCTION reject_courier_test_update()").update();
      }
      assertThat(buffer.metrics().bufferedPoints()).isEqualTo(2);
      assertThat(observation.events).isEmpty();
      buffer.flush();
      assertThat(CourierHttpFixture.recorded(browser.get(secondPort, "/courier/me")))
          .isEqualTo(first.plusMillis(100));
      assertThat(observation.events.getFirst().positions().get(id)).hasSize(2);
      assertThat(buffer.metrics().bufferedPoints()).isZero();
    }
  }

  @Test
  void neverRequeuesCommittedPositionsWhenAfterCommitNotificationFails() throws Exception {
    try (var browser = online()) {
      Instant recorded = Instant.now().plusMillis(100).truncatedTo(ChronoUnit.MICROS);
      browser.positions(port, List.of(new PositionSample(recorded, CourierHttpFixture.NEARBY)));
      observation.fail.set(true);
      long failures = buffer.metrics().flushFailures();
      buffer.flush();
      observation.fail.set(false);
      assertThat(CourierHttpFixture.recorded(browser.get(secondPort, "/courier/me")))
          .isEqualTo(recorded);
      assertThat(buffer.metrics().bufferedPoints()).isZero();
      assertThat(buffer.metrics().flushFailures()).isEqualTo(failures);
      buffer.flush();
      assertThat(observation.events).hasSize(1);
    }
  }

  @Test
  void rejectsInvalidTimesOrderingAndAreasWithoutBufferingPartialBatch() throws Exception {
    try (var browser = online()) {
      var first = new PositionSample(Instant.now().plusMillis(100), CourierHttpFixture.PICKUP);
      assertThat(browser.positions(port, List.of(first, first)).statusCode()).isEqualTo(400);
      assertThat(
              browser
                  .positions(
                      port,
                      List.of(
                          new PositionSample(
                              Instant.now().minusSeconds(121), CourierHttpFixture.PICKUP)))
                  .statusCode())
          .isEqualTo(400);
      assertThat(
              browser
                  .positions(
                      port,
                      List.of(
                          new PositionSample(
                              Instant.now().plusSeconds(6), CourierHttpFixture.PICKUP)))
                  .statusCode())
          .isEqualTo(400);
      assertThat(browser.post(port, "/courier/positions", "{\"positions\":[]}").statusCode())
          .isEqualTo(400);
      assertThat(browser.post(port, "/courier/positions", "{\"positions\":[null]}").statusCode())
          .isEqualTo(400);
      assertThat(
              browser
                  .post(
                      port,
                      "/courier/availability",
                      "{\"online\":true,\"position\":{\"latitude\":-22,\"longitude\":-46}}")
                  .statusCode())
          .isEqualTo(422);
      assertThat(
              browser
                  .post(
                      port,
                      "/courier/availability",
                      "{\"online\":true,\"position\":{\"latitude\":-23.56}}")
                  .statusCode())
          .isEqualTo(400);
      assertThat(buffer.metrics().bufferedPoints()).isZero();
    }
  }

  @Test
  void boundsConfirmationGuessesAcrossIndependentManagers() {
    UUID courier = UUID.randomUUID();
    DemoLimits other = second.getBean(DemoLimits.class);
    for (int attempt = 0; attempt < 10; attempt++) {
      (attempt % 2 == 0 ? limits : other).checkDeliveryConfirmation(courier);
    }
    assertThatThrownBy(() -> limits.checkDeliveryConfirmation(courier))
        .isInstanceOf(ApiException.class)
        .satisfies(failure -> assertThat(((ApiException) failure).status().value()).isEqualTo(429));
  }

  private CourierHttpFixture online() throws Exception {
    var browser = new CourierHttpFixture();
    assertThat(browser.login(port, "COURIER").statusCode()).isEqualTo(200);
    assertThat(browser.online(port).statusCode()).isEqualTo(200);
    return browser;
  }

  static class Observation {
    final List<CourierPositionsPersisted> events = new CopyOnWriteArrayList<>();
    final AtomicBoolean fail = new AtomicBoolean();

    @EventListener
    void receive(CourierPositionsPersisted event) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      events.add(event);
      if (fail.get()) throw new IllegalStateException("Falha controlada na notificação.");
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ObservationConfiguration {
    @Bean
    Observation observation() {
      return new Observation();
    }
  }
}
