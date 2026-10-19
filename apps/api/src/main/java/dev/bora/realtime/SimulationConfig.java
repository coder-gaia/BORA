package dev.bora.realtime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = "simulationSpeed")
public record SimulationConfig(
    @Schema(minimum = "0", exclusiveMinimum = true, maximum = "100") double simulationSpeed) {}
