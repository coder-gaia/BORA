package dev.bora.orders;

import java.time.Instant;
import java.util.Objects;

public record OrderTransition(
    OrderState previous, OrderState next, OrderCommand command, Instant occurredAt) {

  public OrderTransition {
    Objects.requireNonNull(previous, "O estado anterior é obrigatório.");
    Objects.requireNonNull(next, "O próximo estado é obrigatório.");
    Objects.requireNonNull(command, "O comando é obrigatório.");
    Objects.requireNonNull(occurredAt, "O horário é obrigatório.");
  }
}
