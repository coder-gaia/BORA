package dev.bora.routing;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record Route(
    List<GeoPoint> geometry,
    double distanceMeters,
    Duration duration,
    Duration simulatedDuration,
    SnappedPoint origin,
    SnappedPoint destination) {
  public Route {
    geometry = List.copyOf(geometry);
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(simulatedDuration, "simulatedDuration");
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(destination, "destination");
    if (geometry.isEmpty()
        || !Double.isFinite(distanceMeters)
        || distanceMeters < 0
        || duration.isNegative()
        || simulatedDuration.isNegative()) {
      throw new IllegalArgumentException("Rota inválida.");
    }
  }
}
