package dev.bora.dispatch.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

record CancelTrainingOrderRequest(
    @NotNull(message = "Informe a versão atual do treino.") @PositiveOrZero(message = "A versão do treino não pode ser negativa.") Long expectedVersion) {}
