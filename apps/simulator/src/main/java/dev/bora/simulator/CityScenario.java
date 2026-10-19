package dev.bora.simulator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

record CityScenario(
    long seed,
    double speed,
    Instant serverTime,
    List<Actor> couriers,
    List<Actor> stores,
    List<GeoPoint> destinations) {
  CityScenario {
    couriers = List.copyOf(couriers);
    stores = List.copyOf(stores);
    destinations = List.copyOf(destinations);
    if (!Double.isFinite(speed)
        || speed <= 0
        || couriers.isEmpty()
        || stores.isEmpty()
        || destinations.isEmpty()) {
      throw new IllegalArgumentException("O cenário da cidade está incompleto.");
    }
  }

  static CityScenario parse(JsonNode value) {
    List<Actor> couriers = actors(value.path("couriers"));
    List<Actor> stores = actors(value.path("stores"));
    List<GeoPoint> destinations = new ArrayList<>();
    value.path("destinations").forEach(point -> destinations.add(point(point)));
    return new CityScenario(
        value.path("seed").asLong(),
        value.path("speed").asDouble(),
        Instant.parse(value.path("serverTime").asString()),
        couriers,
        stores,
        destinations);
  }

  private static List<Actor> actors(JsonNode values) {
    List<Actor> actors = new ArrayList<>();
    values.forEach(
        value ->
            actors.add(
                new Actor(
                    value.path("index").asInt(),
                    value.path("id").asString(),
                    point(value.path("position")))));
    return actors;
  }

  static GeoPoint point(JsonNode value) {
    if (!value.hasNonNull("latitude") || !value.hasNonNull("longitude")) {
      throw new IllegalArgumentException("A API não informou uma posição válida.");
    }
    return new GeoPoint(value.path("latitude").asDouble(), value.path("longitude").asDouble());
  }

  static RouteMotion motion(JsonNode value, long now) {
    if (!value.path("geometry").isArray()
        || !value.hasNonNull("simulatedDurationMillis")
        || !value.path("simulatedDurationMillis").isIntegralNumber()) {
      throw new IllegalArgumentException("A API não informou uma rota válida.");
    }
    List<GeoPoint> geometry = new ArrayList<>();
    value.path("geometry").forEach(point -> geometry.add(point(point)));
    return new RouteMotion(geometry, value.path("simulatedDurationMillis").asLong(), now);
  }

  record Actor(int index, String id, GeoPoint position) {}
}
