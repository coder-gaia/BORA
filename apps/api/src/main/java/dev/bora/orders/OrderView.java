package dev.bora.orders;

import dev.bora.routing.GeoPoint;
import dev.bora.stores.StoreView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(
    requiredProperties = {
      "id",
      "publicCode",
      "store",
      "dropoff",
      "status",
      "createdAt",
      "slaDeadline",
      "version",
      "timeline"
    })
public record OrderView(
    UUID id,
    String publicCode,
    StoreView store,
    GeoPoint dropoff,
    OrderStatus status,
    UUID courierId,
    Instant createdAt,
    Instant slaDeadline,
    long version,
    String confirmationCode,
    List<OrderEventView> timeline) {
  public OrderView {
    timeline = List.copyOf(timeline);
  }

  public OrderView withConfirmationCode(String code) {
    return new OrderView(
        id,
        publicCode,
        store,
        dropoff,
        status,
        courierId,
        createdAt,
        slaDeadline,
        version,
        code,
        timeline);
  }
}
