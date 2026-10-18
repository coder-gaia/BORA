package dev.bora.orders;

import java.time.Instant;
import java.util.UUID;

public record OrderChange(
    UUID orderId, OrderStatus status, UUID courierId, long version, Instant occurredAt) {}
