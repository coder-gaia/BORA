package dev.bora.realtime.internal;

import dev.bora.orders.OrderStatus;
import dev.bora.realtime.TrackingRoute;
import dev.bora.routing.GeoPoint;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Cache limitado apenas de geometria. A autorização e o estado são sempre lidos do banco. */
@Component
class TrackingRoutes {
  private static final Duration REFRESH = Duration.ofSeconds(10);
  private static final int MAX_ENTRIES = 256;
  private static final int MAX_CALCULATIONS_PER_SECOND = 16;
  private final LinkedHashMap<UUID, Cached> routes = new LinkedHashMap<>(16, 0.75f, true);
  private final Deque<Instant> calculations = new ArrayDeque<>();
  private final RoutingService routing;
  private final Clock clock;

  TrackingRoutes(RoutingService routing, Clock clock) {
    this.routing = routing;
    this.clock = clock;
  }

  synchronized TrackingRoute route(
      UUID orderId,
      long version,
      OrderStatus status,
      UUID courierId,
      GeoPoint pickup,
      GeoPoint dropoff,
      GeoPoint courierPosition) {
    if (status == OrderStatus.DELIVERED || status == OrderStatus.CANCELED) {
      routes.remove(orderId);
      return null;
    }
    boolean moving = status == OrderStatus.ASSIGNED || status == OrderStatus.PICKED_UP;
    if (moving && courierPosition == null) {
      routes.remove(orderId);
      return null;
    }
    Instant now = clock.instant();
    Generation generation = new Generation(version, status, courierId);
    Cached cached = routes.get(orderId);
    if (cached != null
        && cached.generation().equals(generation)
        && !now.isBefore(cached.calculatedAt())
        && now.isBefore(cached.calculatedAt().plus(REFRESH))) {
      return cached.route();
    }
    while (!calculations.isEmpty() && !calculations.getFirst().isAfter(now.minusSeconds(1))) {
      calculations.removeFirst();
    }
    if (calculations.size() >= MAX_CALCULATIONS_PER_SECOND) {
      // Saturação não vira falha em cache: a próxima janela pode servir a rota normalmente.
      return null;
    }
    calculations.addLast(now);
    boolean pickupTarget = status == OrderStatus.ASSIGNED;
    GeoPoint origin = moving ? courierPosition : pickup;
    GeoPoint target = pickupTarget ? pickup : dropoff;
    TrackingRoute result = null;
    try {
      var route = routing.route(origin, target);
      result =
          new TrackingRoute(
              pickupTarget ? TrackingRoute.Destination.PICKUP : TrackingRoute.Destination.DROPOFF,
              route.geometry(),
              route.distanceMeters(),
              route.duration().toMillis(),
              route.simulatedDuration().toMillis(),
              now);
    } catch (RoutingException unavailable) {
      // O estado e a linha do tempo continuam disponíveis quando o grafo não puder servir a rota.
    }
    routes.put(orderId, new Cached(generation, now, result));
    if (routes.size() > MAX_ENTRIES) {
      routes.remove(routes.keySet().iterator().next());
    }
    return result;
  }

  private record Generation(long version, OrderStatus status, UUID courierId) {}

  private record Cached(Generation generation, Instant calculatedAt, TrackingRoute route) {}
}
