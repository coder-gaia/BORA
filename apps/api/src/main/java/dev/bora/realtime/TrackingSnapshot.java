package dev.bora.realtime;

import dev.bora.orders.OrderStatus;
import dev.bora.routing.GeoPoint;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** O link concede somente o progresso e a posicao da entrega correspondente. */
@Schema(
    requiredProperties = {
      "status", "version", "storeName", "pickup", "dropoff", "courierPosition",
      "courierPositionUpdatedAt", "createdAt", "timeline", "route", "etaSeconds", "serverTime"
    })
public record TrackingSnapshot(
    OrderStatus status,
    long version,
    String storeName,
    GeoPoint pickup,
    GeoPoint dropoff,
    @Schema(nullable = true) GeoPoint courierPosition,
    @Schema(nullable = true) Instant courierPositionUpdatedAt,
    Instant createdAt,
    List<TrackingEvent> timeline,
    @Schema(nullable = true) TrackingRoute route,
    @Schema(
            nullable = true,
            description =
                "Estimativa em segundos simulados até o próximo marco, calculada na data da rota.")
        Long etaSeconds,
    Instant serverTime) {
  public TrackingSnapshot {
    timeline = List.copyOf(timeline);
  }
}
