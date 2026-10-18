package dev.bora.couriers;

import dev.bora.routing.GeoPoint;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(requiredProperties = {"id", "name", "status", "location", "locationUpdatedAt", "version"})
public record CourierView(
    UUID id,
    String name,
    CourierStatus status,
    @Schema(nullable = true) GeoPoint location,
    @Schema(nullable = true) Instant locationUpdatedAt,
    long version) {}
