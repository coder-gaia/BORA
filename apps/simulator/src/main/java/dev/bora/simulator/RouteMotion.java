package dev.bora.simulator;

import java.time.Duration;
import java.util.List;

/** Percorre os segmentos devolvidos pelo roteador, sem cortar esquinas em linha reta. */
final class RouteMotion {
  private final List<GeoPoint> geometry;
  private final double[] cumulative;
  private final long durationNanos;
  private final long startedAt;

  RouteMotion(List<GeoPoint> geometry, long simulatedDurationMillis, long startedAt) {
    if (geometry.isEmpty() || simulatedDurationMillis < 0) {
      throw new IllegalArgumentException("Rota inválida.");
    }
    this.geometry = List.copyOf(geometry);
    this.cumulative = new double[geometry.size()];
    for (int i = 1; i < geometry.size(); i++) {
      cumulative[i] = cumulative[i - 1] + geometry.get(i - 1).distanceTo(geometry.get(i));
    }
    this.durationNanos = Duration.ofMillis(simulatedDurationMillis).toNanos();
    this.startedAt = startedAt;
  }

  GeoPoint at(long now) {
    long elapsed = Math.max(0, now - startedAt);
    if (durationNanos == 0 || elapsed >= durationNanos || geometry.size() == 1) {
      return geometry.getLast();
    }
    double target = cumulative[cumulative.length - 1] * elapsed / durationNanos;
    for (int i = 1; i < cumulative.length; i++) {
      if (target <= cumulative[i]) {
        double length = cumulative[i] - cumulative[i - 1];
        double fraction = length == 0 ? 1 : (target - cumulative[i - 1]) / length;
        return geometry.get(i - 1).interpolate(geometry.get(i), fraction);
      }
    }
    return geometry.getLast();
  }

  boolean finished(long now) {
    return Math.max(0, now - startedAt) >= durationNanos;
  }
}
