package dev.bora.couriers;

import java.time.Instant;
import java.util.Objects;

public record CourierTransition(
    CourierState previous, CourierState next, CourierCommand command, Instant occurredAt) {

  public CourierTransition {
    Objects.requireNonNull(previous, "O estado anterior é obrigatório.");
    Objects.requireNonNull(next, "O próximo estado é obrigatório.");
    Objects.requireNonNull(command, "O comando é obrigatório.");
    Objects.requireNonNull(occurredAt, "O horário é obrigatório.");
  }
}
