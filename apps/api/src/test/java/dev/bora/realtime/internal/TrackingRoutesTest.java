package dev.bora.realtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.bora.orders.OrderStatus;
import dev.bora.realtime.TrackingRoute;
import dev.bora.routing.GeoPoint;
import dev.bora.routing.Route;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingService;
import dev.bora.routing.SnappedPoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TrackingRoutesTest {
  private static final GeoPoint PICKUP = new GeoPoint(-23.5614, -46.6559);
  private static final GeoPoint DROPOFF = new GeoPoint(-23.5622, -46.6549);
  private static final GeoPoint POSITION = new GeoPoint(-23.561, -46.656);
  private final RoutingService routing = mock(RoutingService.class);
  private final Clock clock = mock(Clock.class);
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-10-20T12:00:00Z"));
  private final TrackingRoutes routes = new TrackingRoutes(routing, clock);
  private final UUID id = UUID.randomUUID();
  private final UUID courier = UUID.randomUUID();

  @BeforeEach
  void availableGraph() {
    when(clock.instant()).thenAnswer(ignored -> now.get());
    doAnswer(
            call -> {
              GeoPoint origin = call.getArgument(0);
              GeoPoint target = call.getArgument(1);
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
  void limitsRepeatedPositionUpdatesToOneCalculationPerTenSeconds() {
    TrackingRoute first = lookup(id, 1, OrderStatus.ASSIGNED, courier, POSITION);
    for (int sample = 0; sample < 120; sample++) {
      assertThat(
              lookup(
                  id,
                  1,
                  OrderStatus.ASSIGNED,
                  courier,
                  new GeoPoint(POSITION.latitude() + sample * 0.000001, POSITION.longitude())))
          .isSameAs(first);
    }
    now.set(now.get().plusMillis(9_999));
    assertThat(lookup(id, 1, OrderStatus.ASSIGNED, courier, POSITION)).isSameAs(first);
    verify(routing, times(1)).route(any(), any());
    now.set(now.get().plusMillis(1));
    assertThat(lookup(id, 1, OrderStatus.ASSIGNED, courier, POSITION).calculatedAt())
        .isEqualTo(now.get());
    verify(routing, times(2)).route(any(), any());
  }

  @Test
  void changesGenerationImmediatelyWhenTheCourierOrDeliveryStageChanges() {
    assertThat(lookup(id, 1, OrderStatus.ASSIGNED, courier, POSITION).destination())
        .isEqualTo(TrackingRoute.Destination.PICKUP);
    lookup(id, 2, OrderStatus.ASSIGNED, courier, POSITION);
    lookup(id, 2, OrderStatus.ASSIGNED, UUID.randomUUID(), POSITION);
    assertThat(lookup(id, 3, OrderStatus.PICKED_UP, courier, POSITION).destination())
        .isEqualTo(TrackingRoute.Destination.DROPOFF);
    verify(routing, times(4)).route(any(), any());
  }

  @Test
  void doesNotKeepACourierRouteWhenThePositionIsStaleOrTheOrderEnds() {
    lookup(id, 1, OrderStatus.ASSIGNED, courier, POSITION);
    assertThat(lookup(id, 1, OrderStatus.ASSIGNED, courier, null)).isNull();
    lookup(id, 1, OrderStatus.ASSIGNED, courier, POSITION);
    assertThat(lookup(id, 2, OrderStatus.CANCELED, courier, POSITION)).isNull();
    assertThat(lookup(id, 4, OrderStatus.DELIVERED, courier, POSITION)).isNull();
    verify(routing, times(2)).route(any(), any());
  }

  @Test
  void limitsUnavailableGraphRetriesAndRecoversAfterTheSameRefreshInterval() {
    doThrow(new RoutingException(RoutingException.Reason.GRAPH_UNAVAILABLE, "Sem grafo."))
        .when(routing)
        .route(any(), any());
    for (int sample = 0; sample < 120; sample++) {
      assertThat(lookup(id, 1, OrderStatus.ASSIGNED, courier, POSITION)).isNull();
    }
    verify(routing, times(1)).route(any(), any());
    availableGraph();
    now.set(now.get().plusSeconds(10));
    assertThat(lookup(id, 1, OrderStatus.ASSIGNED, courier, POSITION)).isNotNull();
    verify(routing, times(2)).route(any(), any());
  }

  @Test
  void boundsTheCacheAndEvictsTheLeastRecentlyUsedOrder() {
    List<UUID> orders =
        java.util.stream.IntStream.range(0, 256).mapToObj(i -> UUID.randomUUID()).toList();
    orders.forEach(
        order -> {
          now.set(now.get().plusMillis(63));
          assertThat(lookup(order, 1, OrderStatus.ASSIGNED, courier, POSITION)).isNotNull();
        });
    now.set(now.get().plusMillis(63));
    lookup(orders.getFirst(), 1, OrderStatus.ASSIGNED, courier, POSITION);
    now.set(now.get().plusMillis(63));
    lookup(UUID.randomUUID(), 1, OrderStatus.ASSIGNED, courier, POSITION);
    // Preencher 256 entradas com orçamento de 16/s ultrapassa o TTL. Inspecionar as chaves
    // distingue a evicção LRU da expiração, sem retirar o orçamento nem aumentar o TTL no teste.
    Object cached = ReflectionTestUtils.getField(routes, "routes");
    assertThat(cached).isInstanceOf(java.util.Map.class);
    java.util.Map<?, ?> entries = (java.util.Map<?, ?>) cached;
    assertThat(entries).hasSize(256);
    assertThat(entries.containsKey(orders.getFirst())).isTrue();
    assertThat(entries.containsKey(orders.get(1))).isFalse();
    verify(routing, times(258)).route(any(), any());
  }

  @Test
  void boundsCalculationsAcrossOrdersAndRetriesBudgetMissesInTheNextSlidingWindow() {
    List<UUID> orders =
        java.util.stream.IntStream.range(0, 17).mapToObj(i -> UUID.randomUUID()).toList();
    for (int index = 0; index < 16; index++) {
      assertThat(lookup(orders.get(index), 1, OrderStatus.ASSIGNED, courier, POSITION)).isNotNull();
    }
    assertThat(lookup(orders.get(16), 1, OrderStatus.ASSIGNED, courier, POSITION)).isNull();
    // Uma geometria já disponível não precisa de orçamento novo.
    assertThat(lookup(orders.getFirst(), 1, OrderStatus.ASSIGNED, courier, POSITION)).isNotNull();
    now.set(now.get().plusMillis(999));
    assertThat(lookup(orders.get(16), 1, OrderStatus.ASSIGNED, courier, POSITION)).isNull();
    verify(routing, times(16)).route(any(), any());
    now.set(now.get().plusMillis(1));
    assertThat(lookup(orders.get(16), 1, OrderStatus.ASSIGNED, courier, POSITION)).isNotNull();
    verify(routing, times(17)).route(any(), any());
  }

  @Test
  void providesOnlyThePlannedDeliveryLegWhileSearchingOrWaitingAtTheStore() {
    assertThat(lookup(id, 0, OrderStatus.SEARCHING, null, null).geometry())
        .containsExactly(PICKUP, DROPOFF);
    assertThat(lookup(id, 2, OrderStatus.AT_STORE, courier, POSITION).geometry())
        .containsExactly(PICKUP, DROPOFF);
    verify(routing, times(2)).route(PICKUP, DROPOFF);
  }

  private TrackingRoute lookup(
      UUID order, long version, OrderStatus status, UUID courierId, GeoPoint position) {
    return routes.route(order, version, status, courierId, PICKUP, DROPOFF, position);
  }
}
