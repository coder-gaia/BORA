package dev.bora.orders;

import java.time.Instant;
import java.util.UUID;

public record OrderResourcesReleased(
    UUID orderId,
    OrderStatus previousStatus,
    UUID previousCourierId,
    Reason reason,
    Instant occurredAt,
    long orderVersion) {
  public enum Reason {
    CANCELED,
    REASSIGNED,
    DELIVERED
  }
}
