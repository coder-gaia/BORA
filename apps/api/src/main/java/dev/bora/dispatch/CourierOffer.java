package dev.bora.dispatch;

import dev.bora.routing.GeoPoint;
import dev.bora.stores.StoreView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(
    requiredProperties = {
      "id",
      "orderId",
      "store",
      "dropoff",
      "etaToPickupSeconds",
      "createdAt",
      "expiresAt"
    })
public record CourierOffer(
    UUID id,
    UUID orderId,
    StoreView store,
    GeoPoint dropoff,
    @Schema(description = "Tempo até a loja em segundos, já ajustado à velocidade da simulação.")
        int etaToPickupSeconds,
    Instant createdAt,
    Instant expiresAt) {}
