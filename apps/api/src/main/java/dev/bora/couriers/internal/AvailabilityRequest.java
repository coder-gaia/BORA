package dev.bora.couriers.internal;

import dev.bora.routing.GeoPoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record AvailabilityRequest(
    @NotNull(message = "Informe se deseja ficar online.") Boolean online,
    @Valid GeoPoint position) {}
