package dev.bora.fulfillment.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record DeliverRequest(
    @NotNull(message = "Informe a versão atual do pedido.") @PositiveOrZero(message = "A versão do pedido não pode ser negativa.") Long expectedVersion,
    @Pattern(regexp = "[0-9]{6}", message = "Informe os seis números do código de confirmação.") String confirmationCode) {}
