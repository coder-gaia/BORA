package dev.bora.orders;

import dev.bora.routing.GeoPoint;
import dev.bora.stores.StoreView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
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
      "searchingSince"
    })
public record OrderSummary(
    UUID id,
    String publicCode,
    StoreView store,
    GeoPoint dropoff,
    OrderStatus status,
    UUID courierId,
    Instant createdAt,
    Instant slaDeadline,
    long version,
    @Schema(
            nullable = true,
            description = "Início da tentativa atual de busca; ausente nas demais etapas.")
        Instant searchingSince) {}
