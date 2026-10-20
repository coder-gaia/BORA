package dev.bora.orders;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Estado limitado da intenção, sem o código reservado à criação ou repetição autorizada. */
@Schema(requiredProperties = {"orderId", "status", "version", "serverTime"})
public record TrainingOrderState(
    UUID orderId, OrderStatus status, long version, Instant serverTime) {}
