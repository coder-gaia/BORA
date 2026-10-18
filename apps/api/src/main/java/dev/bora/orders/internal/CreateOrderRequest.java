package dev.bora.orders.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(
    @NotNull(message = "Informe o destino.") @Valid DropoffRequest dropoff) {}
