package dev.bora.fulfillment.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PickUpRequest(
    @NotNull(message = "Informe a versão atual do pedido.") @PositiveOrZero(message = "A versão do pedido não pode ser negativa.") Long expectedVersion) {}
