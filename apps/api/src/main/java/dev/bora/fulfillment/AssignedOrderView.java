package dev.bora.fulfillment;

import dev.bora.orders.OrderStatus;
import dev.bora.routing.GeoPoint;
import dev.bora.stores.StoreView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(
    requiredProperties = {
      "id",
      "status",
      "version",
      "store",
      "dropoff",
      "assignedAt",
      "confirmationRequired"
    })
public record AssignedOrderView(
    UUID id,
    OrderStatus status,
    long version,
    StoreView store,
    GeoPoint dropoff,
    Instant assignedAt,
    Instant atStoreAt,
    Instant pickedUpAt,
    boolean confirmationRequired) {}
