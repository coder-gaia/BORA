package dev.bora.orders.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record CancelOrderRequest(
    @NotNull(message = "Informe a versão atual do pedido.") @PositiveOrZero(message = "A versão do pedido não pode ser negativa.") Long expectedVersion) {}
