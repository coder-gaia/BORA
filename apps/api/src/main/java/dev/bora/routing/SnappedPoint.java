package dev.bora.routing;

import java.util.Objects;

public record SnappedPoint(GeoPoint requested, GeoPoint matched, double distanceMeters) {
  public SnappedPoint {
    Objects.requireNonNull(requested, "requested");
    Objects.requireNonNull(matched, "matched");
    if (!Double.isFinite(distanceMeters) || distanceMeters < 0) {
      throw new IllegalArgumentException("Distância de encaixe inválida.");
    }
  }
}
