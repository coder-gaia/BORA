package dev.bora.orders;

import dev.bora.routing.GeoPoint;
import dev.bora.stores.StoreView;
import java.time.Instant;
import java.util.UUID;

public record OrderWorkItem(
    UUID id,
    StoreView store,
    GeoPoint dropoff,
    OrderStatus status,
    UUID courierId,
    UUID visitorContextId,
    long version,
    boolean simulated,
    Instant createdAt,
    Instant assignedAt,
    Instant atStoreAt,
    Instant pickedUpAt) {}
