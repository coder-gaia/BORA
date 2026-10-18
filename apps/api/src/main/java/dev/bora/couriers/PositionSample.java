package dev.bora.couriers;

import dev.bora.routing.GeoPoint;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

@Schema(requiredProperties = {"recordedAt", "position"})
public record PositionSample(
    @NotNull(message = "Informe o horário da posição.") Instant recordedAt,
    @NotNull(message = "Informe as coordenadas da posição.") @Valid GeoPoint position) {}
