package dev.bora.dispatch.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

record ReassignOrderRequest(
    @NotNull(message = "Informe a versão do pedido.") @PositiveOrZero(message = "A versão do pedido não pode ser negativa.") Long expectedVersion) {}
