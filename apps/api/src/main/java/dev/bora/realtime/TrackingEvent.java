package dev.bora.realtime;

import dev.bora.orders.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Somente marcos da entrega, sem identificadores globais, atores ou payload privado. */
@Schema(requiredProperties = {"type", "fromStatus", "toStatus", "occurredAt", "orderVersion"})
public record TrackingEvent(
    String type,
    @Schema(nullable = true) OrderStatus fromStatus,
    OrderStatus toStatus,
    Instant occurredAt,
    long orderVersion) {}
