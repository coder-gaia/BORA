package dev.bora.realtime;

import dev.bora.routing.GeoPoint;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(
    requiredProperties = {
      "destination",
      "geometry",
      "distanceMeters",
      "durationMillis",
      "simulatedDurationMillis",
      "calculatedAt"
    })
public record TrackingRoute(
    Destination destination,
    List<GeoPoint> geometry,
    double distanceMeters,
    long durationMillis,
    long simulatedDurationMillis,
    Instant calculatedAt) {
  public TrackingRoute {
    geometry = List.copyOf(geometry);
  }

  public enum Destination {
    PICKUP,
    DROPOFF
  }
}
