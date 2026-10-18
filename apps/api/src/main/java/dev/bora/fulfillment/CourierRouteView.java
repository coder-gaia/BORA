package dev.bora.fulfillment;

import dev.bora.routing.GeoPoint;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(
    requiredProperties = {
      "orderId",
      "destination",
      "target",
      "geometry",
      "distanceMeters",
      "durationMillis",
      "simulatedDurationMillis"
    })
public record CourierRouteView(
    UUID orderId,
    Destination destination,
    GeoPoint target,
    List<GeoPoint> geometry,
    double distanceMeters,
    long durationMillis,
    long simulatedDurationMillis) {
  public CourierRouteView {
    geometry = List.copyOf(geometry);
  }

  public enum Destination {
    PICKUP,
    DROPOFF
  }
}
