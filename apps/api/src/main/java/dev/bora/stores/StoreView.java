package dev.bora.stores;

import dev.bora.routing.GeoPoint;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(requiredProperties = {"id", "name", "location", "serviceAreaId"})
public record StoreView(UUID id, String name, GeoPoint location, UUID serviceAreaId) {}
