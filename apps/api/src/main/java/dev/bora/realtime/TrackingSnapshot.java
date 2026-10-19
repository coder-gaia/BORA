package dev.bora.realtime;

import dev.bora.orders.OrderStatus;
import dev.bora.routing.GeoPoint;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** O link concede somente o progresso e a posicao da entrega correspondente. */
@Schema(requiredProperties = {"status", "version", "storeName", "pickup", "dropoff", "serverTime"})
public record TrackingSnapshot(
    OrderStatus status,
    long version,
    String storeName,
    GeoPoint pickup,
    GeoPoint dropoff,
    @Schema(nullable = true) GeoPoint courierPosition,
    Instant serverTime) {}
