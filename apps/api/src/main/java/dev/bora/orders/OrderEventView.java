package dev.bora.orders;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(requiredProperties = {"id", "type", "toStatus", "occurredAt", "orderVersion"})
public record OrderEventView(
    long id,
    String type,
    OrderStatus fromStatus,
    OrderStatus toStatus,
    Instant occurredAt,
    long orderVersion) {}
