package dev.bora.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bora.routing.GeoPoint;
import dev.bora.routing.Route;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphHopperRoutingEngineTest {
  private static final String MANIFEST = "routing/synthetic-manifest.json";
  private static final GeoPoint WEST = new GeoPoint(-23.5500, -46.6340);
  private static final GeoPoint SOUTH = new GeoPoint(-23.5510, -46.6330);
  @TempDir static Path root;

  @BeforeAll
  static void prepare() {
    RoutingTools.prepareGraph(MANIFEST, root);
  }

  @Test
  void routesAndSnapsWithConsistentUnitsAndSimulationSpeed() {
    try (var engine = open(3)) {
      Route route = engine.route(WEST, SOUTH);
      assertThat(engine.isReady()).isTrue();
      assertThat(route.distanceMeters()).isBetween(150d, 400d);
      assertThat(route.duration().toMillis()).isPositive();
      assertThat(route.simulatedDuration().toMillis())
          .isEqualTo((long) Math.ceil(route.duration().toMillis() / 3d));
      assertThat(route.origin().matched().latitude())
          .isCloseTo(WEST.latitude(), org.assertj.core.data.Offset.offset(0.00001));
      assertThat(route.destination().distanceMeters()).isLessThan(1);
      var snap = engine.snap(new GeoPoint(-23.55002, -46.6335));
      assertThat(snap.distanceMeters()).isBetween(1d, 5d);
      assertThat(snap.matched().latitude())
          .isCloseTo(-23.55, org.assertj.core.data.Offset.offset(0.00001));
      assertThatThrownBy(() -> route.geometry().clear())
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Test
  void respectsImportedTurnRestrictionAndTakesTheLongerPath() {
    try (var engine = open(1)) {
      Route route = engine.route(new GeoPoint(-23.55, -46.6335), new GeoPoint(-23.5495, -46.633));
      assertThat(route.distanceMeters()).isGreaterThan(200);
      assertThat(route.geometry()).anyMatch(point -> point.latitude() > -23.5491);
      assertThat(route.simulatedDuration()).isEqualTo(route.duration());
    }
  }

  @Test
  void cannotRouteThroughABlockedBarrier() {
    try (var engine = open(1)) {
      assertThatThrownBy(
              () -> engine.route(new GeoPoint(-23.5485, -46.632), new GeoPoint(-23.5475, -46.632)))
          .isInstanceOf(RoutingException.class)
          .satisfies(
              exception ->
                  assertThat(((RoutingException) exception).reason())
                      .isIn(
                          RoutingException.Reason.NO_ROUTE, RoutingException.Reason.SNAP_TOO_FAR));
    }
  }

  @Test
  void allowsCoincidentPointsAndRejectsDistantSnapping() {
    try (var engine = open(3)) {
      Route route = engine.route(WEST, WEST);
      assertThat(route.distanceMeters()).isZero();
      assertThat(route.duration()).isEqualTo(java.time.Duration.ZERO);
      assertThat(route.simulatedDuration()).isEqualTo(java.time.Duration.ZERO);
      assertThatThrownBy(() -> engine.snap(new GeoPoint(-24, -47)))
          .isInstanceOf(RoutingException.class)
          .satisfies(
              exception ->
                  assertThat(((RoutingException) exception).reason())
                      .isEqualTo(RoutingException.Reason.SNAP_TOO_FAR));
    }
  }

  @Test
  void twoReadOnlyReadersHandleConcurrentRoutesWithoutChangingFiles() throws Exception {
    Map<Path, String> before = snapshot();
    try (var first = open(3);
        var second = open(3);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var calls =
          java.util.stream.IntStream.range(0, 40)
              .mapToObj(
                  index ->
                      executor.submit(() -> (index % 2 == 0 ? first : second).route(WEST, SOUTH)))
              .toList();
      for (var call : calls) {
        assertThat(call.get().distanceMeters()).isPositive();
      }
    }
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void missingGraphStaysUnavailableAndDoesNotCreateItsDirectory() {
    Path absent = root.resolve("absent");
    try (var engine =
        new GraphHopperRoutingEngine(RoutingGraphSpec.loadManifest(MANIFEST), absent, 3, 150)) {
      engine.initialize();
      assertThat(engine.isReady()).isFalse();
      assertThat(Files.exists(absent)).isFalse();
      assertThatThrownBy(() -> engine.route(WEST, SOUTH))
          .isInstanceOf(RoutingException.class)
          .satisfies(
              exception ->
                  assertThat(((RoutingException) exception).reason())
                      .isEqualTo(RoutingException.Reason.GRAPH_UNAVAILABLE));
    }
  }

  @Test
  void aFailedWarmupKeepsTheExistingGraphUnavailableWithoutModifyingIt() throws Exception {
    Map<Path, String> before = snapshot();
    try (var engine =
        new GraphHopperRoutingEngine(
            RoutingGraphSpec.loadManifest("routing/synthetic-unreachable-manifest.json"),
            root,
            3,
            150)) {
      engine.initialize();
      assertThat(engine.isReady()).isFalse();
    }
    assertThat(snapshot()).isEqualTo(before);
  }

  private static GraphHopperRoutingEngine open(double speed) {
    var engine =
        new GraphHopperRoutingEngine(RoutingGraphSpec.loadManifest(MANIFEST), root, speed, 150);
    engine.initialize();
    assertThat(engine.isReady()).isTrue();
    return engine;
  }

  private static Map<Path, String> snapshot() throws Exception {
    Map<Path, String> values = new LinkedHashMap<>();
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
        values.put(
            root.relativize(file),
            Files.getLastModifiedTime(file)
                + ":"
                + RoutingGraphSpec.sha256(Files.readAllBytes(file)));
      }
    }
    return values;
  }
}
