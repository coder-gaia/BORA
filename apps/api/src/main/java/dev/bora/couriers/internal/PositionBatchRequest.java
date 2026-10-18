package dev.bora.couriers.internal;

import dev.bora.couriers.PositionSample;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PositionBatchRequest(
    @NotNull(message = "Informe as posições.") @Size(min = 1, max = 40, message = "Envie de 1 a 40 posições por lote.") List<@NotNull(message = "Informe uma posição válida.") @Valid PositionSample> positions) {}
