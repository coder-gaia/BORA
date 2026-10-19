package dev.bora.simulator;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SimulationRulesTest {
  @Test
  void followsRouteSegmentsAndUsesAlreadyAcceleratedDurationOnce() {
    GeoPoint start = new GeoPoint(-23.56, -46.66);
    GeoPoint corner = new GeoPoint(-23.56, -46.65);
    GeoPoint finish = new GeoPoint(-23.55, -46.65);
    RouteMotion route = new RouteMotion(List.of(start, corner, finish), 20_000, 10);
    assertEquals(start, route.at(10));
    GeoPoint early = route.at(Duration.ofSeconds(5).toNanos() + 10);
    assertEquals(start.latitude(), early.latitude(), 0.000_001);
    assertTrue(early.longitude() > start.longitude() && early.longitude() < corner.longitude());
    GeoPoint late = route.at(Duration.ofSeconds(15).toNanos() + 10);
    assertEquals(finish.longitude(), late.longitude(), 0.000_001);
    assertTrue(late.latitude() > corner.latitude() && late.latitude() < finish.latitude());
    assertFalse(route.finished(Duration.ofSeconds(19).toNanos() + 10));
    assertTrue(route.finished(Duration.ofSeconds(20).toNanos() + 10));
    assertEquals(finish, route.at(Duration.ofSeconds(20).toNanos() + 10));
    assertEquals(finish, route.at(Duration.ofDays(1).toNanos() + 10));
  }

  @Test
  void stationaryAndDuplicateVerticesRemainFinite() {
    GeoPoint point = new GeoPoint(-23.56, -46.66);
    assertEquals(point, new RouteMotion(List.of(point), 0, 0).at(0));
    assertEquals(point, new RouteMotion(List.of(point, point), 1_000, 0).at(500_000_000));
    assertThrows(IllegalArgumentException.class, () -> new RouteMotion(List.of(), 10, 0));
  }

  @Test
  void demandUsesSaoPauloWallClockWithLunchDinnerPeaksAndNightFloor() {
    DemandCurve curve = new DemandCurve();
    assertEquals(6, curve.ordersPerMinute(Instant.parse("2026-10-19T06:00:00Z")));
    assertEquals(18, curve.ordersPerMinute(Instant.parse("2026-10-19T15:00:00Z")));
    assertEquals(22, curve.ordersPerMinute(Instant.parse("2026-10-19T22:00:00Z")));
    for (int hour = 0; hour < 24; hour++) {
      assertTrue(
          curve.ordersPerMinute(Instant.parse("2026-10-19T00:00:00Z").plusSeconds(hour * 3_600))
              >= 6);
    }
    assertEquals(19, curve.ordersPerMinute(Instant.parse("2026-10-19T21:30:00Z")));
  }

  @Test
  void demandRegionsInvertBetweenLunchAndDinnerAndDriveTheSameStoreSchedule() {
    DemandCurve curve = new DemandCurve();
    Instant lunch = Instant.parse("2026-10-19T15:00:00Z");
    Instant dinner = Instant.parse("2026-10-19T22:00:00Z");
    assertEquals(1.6, curve.storeWeight(0, 40, lunch));
    assertEquals(0.4, curve.storeWeight(30, 40, lunch));
    assertEquals(0.4, curve.storeWeight(0, 40, dinner));
    assertEquals(1.6, curve.storeWeight(30, 40, dinner));
    assertEquals(
        40,
        java.util.stream.IntStream.range(0, 40)
            .mapToDouble(index -> curve.storeWeight(index, 40, lunch))
            .sum());
    var first = new SplittableRandom(17);
    var second = new SplittableRandom(17);
    long high = curve.nextOrder(lunch, 40, 1.6, first).toMillis();
    long low = curve.nextOrder(lunch, 40, 0.4, second).toMillis();
    assertEquals(4, low / (double) high, 0.001);
  }

  @Test
  void actorRandomStreamsAreReproducibleAndIndependentOfThreadInterleaving() {
    var first = DemandCurve.actorRandom(2026, "COURIER", 17);
    var second = DemandCurve.actorRandom(2026, "COURIER", 17);
    var unrelated = DemandCurve.actorRandom(2026, "COURIER", 18);
    for (int i = 0; i < 1_000; i++) {
      unrelated.nextLong();
      assertEquals(first.nextLong(), second.nextLong());
    }
    assertNotEquals(
        DemandCurve.actorRandom(2026, "STORE", 17).nextLong(),
        DemandCurve.actorRandom(2026, "COURIER", 17).nextLong());
  }

  @Test
  void offerAcceptanceAndWaitDistributionMatchTheScenario() {
    SplittableRandom random = new SplittableRandom(33);
    int accepts = 0;
    int rejects = 0;
    int timeouts = 0;
    for (int i = 0; i < 100_000; i++) {
      var decision = DemandCurve.offerDecision(random);
      assertTrue(decision.delay().toMillis() >= 3_000 && decision.delay().toMillis() <= 12_000);
      switch (decision.action()) {
        case ACCEPT -> accepts++;
        case REJECT -> rejects++;
        case TIMEOUT -> timeouts++;
      }
      Duration wait = DemandCurve.storeWait(random, 3);
      assertTrue(wait.toMillis() >= 20_000 && wait.toMillis() <= 60_000);
    }
    assertEquals(0.85, accepts / 100_000.0, 0.005);
    assertEquals(0.10, rejects / 100_000.0, 0.005);
    assertEquals(0.05, timeouts / 100_000.0, 0.005);
  }

  @Test
  void poissonStoreSchedulesHaveTheExpectedCityRateWithoutStartupBurst() {
    DemandCurve curve = new DemandCurve();
    var random = new SplittableRandom(9);
    Instant lunch = Instant.parse("2026-10-19T15:00:00Z");
    double totalMillis = 0;
    for (int i = 0; i < 100_000; i++) {
      Duration wait = curve.nextOrder(lunch, 40, random);
      assertTrue(wait.toMillis() >= 1_000);
      totalMillis += wait.toMillis();
    }
    double observedRate = 60_000 * 40 / (totalMillis / 100_000);
    assertEquals(18, observedRate, 0.2);
  }

  @Test
  void positionClockIgnoresLocalWallClockAndKeepsMicrosecondsStrictlyIncreasing() {
    AtomicLong monotonic = new AtomicLong(50);
    ServerClock clock = new ServerClock(monotonic::get);
    Instant server = Instant.parse("2026-10-19T12:00:00Z");
    assertThrows(IllegalStateException.class, clock::instant);
    clock.synchronize(server);
    Instant first = clock.nextPosition(null);
    Instant second = clock.nextPosition(first);
    assertEquals(first.plusNanos(1_000), second);
    monotonic.addAndGet(2_000_000_000);
    assertEquals(server.plusSeconds(2), clock.instant());
    clock.synchronize(server.minusSeconds(60));
    assertEquals(server.plusSeconds(2), clock.instant());
  }

  @Test
  void healthRecoversFromOneBadResponseButFailsOnPersistentContractErrorOrFatalActor() {
    SimulatorStats stats = new SimulatorStats(Instant.now());
    stats.provisioned(1);
    stats.response(200);
    assertFalse(stats.healthy());
    stats.courier(0, "AVAILABLE");
    assertTrue(stats.healthy());
    stats.invalidResponse(0);
    assertTrue(stats.healthy());
    stats.invalidResponse(0);
    stats.invalidResponse(0);
    assertFalse(stats.healthy());
    stats.healthyTick(0);
    assertTrue(stats.healthy());
    stats.count("fatalActorFailures");
    assertFalse(stats.healthy());
  }

  @Test
  void gpsHeartbeatKeepsCourierOnlineWithoutInventingOrChangingItsStatus() {
    AtomicLong monotonic = new AtomicLong(1);
    SimulatorStats stats = new SimulatorStats(Instant.now(), monotonic::get);
    stats.provisioned(1);
    stats.courier(0, "AVAILABLE");
    stats.response(200);
    assertTrue(stats.healthy());
    monotonic.addAndGet(Duration.ofSeconds(31).toNanos());
    stats.response(202);
    assertFalse(stats.healthy());
    stats.courierHeartbeat(0);
    assertTrue(stats.healthy());
    assertEquals(Map.of("AVAILABLE", 1L), stats.snapshot(Instant.now()).get("couriersByStatus"));
    stats.courier(0, "OFFLINE");
    stats.courierHeartbeat(0);
    assertFalse(stats.healthy());
    stats.courierHeartbeat(1);
    assertEquals(1, stats.snapshot(Instant.now()).get("couriersKnown"));
  }

  @Test
  void internalHttpCookieExceptionIsOptInAndRestrictedToApprovedOrigins() {
    String secret = "a".repeat(48);
    for (String rejected :
        List.of(
            "http://example.com:8080",
            "http://api1:80",
            "https://api1:8080",
            "http://api1.example.com:8080")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              SimulatorConfig.from(
                  Map.of(
                      "BORA_SIMULATOR_KEY",
                      secret,
                      "BORA_API_URL",
                      rejected,
                      "BORA_SIMULATOR_INTERNAL_HTTP",
                      "true")));
    }
    for (String allowed :
        List.of(
            "http://api1:8080",
            "http://api2:8080",
            "http://api:8080",
            "http://127.0.0.1:32123",
            "http://localhost:54321")) {
      assertTrue(
          SimulatorConfig.from(
                  Map.of(
                      "BORA_SIMULATOR_KEY",
                      secret,
                      "BORA_API_URL",
                      allowed,
                      "BORA_SIMULATOR_INTERNAL_HTTP",
                      "true"))
              .internalHttp());
    }
    assertFalse(
        SimulatorConfig.from(
                Map.of("BORA_SIMULATOR_KEY", secret, "BORA_API_URL", "https://example.com"))
            .internalHttp());
  }

  @Test
  void rejectsConfigurationThatCouldLeakSecretInOriginOrUseInvalidSettings() {
    String secret = "a".repeat(48);
    assertThrows(IllegalArgumentException.class, () -> SimulatorConfig.from(Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SimulatorConfig.from(
                Map.of(
                    "BORA_SIMULATOR_KEY",
                    secret,
                    "BORA_API_URL",
                    "https://user:password@example.org")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SimulatorConfig.from(
                Map.of("BORA_SIMULATOR_KEY", secret, "SIM_DURATION_SECONDS", "-1")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SimulatorConfig.from(Map.of("BORA_SIMULATOR_KEY", secret, "SIM_COURIER_COUNT", "121")));
    assertEquals(
        Duration.ofHours(1),
        SimulatorConfig.from(Map.of("BORA_SIMULATOR_KEY", secret, "SIM_DURATION_SECONDS", "3600"))
            .duration());
  }
}
