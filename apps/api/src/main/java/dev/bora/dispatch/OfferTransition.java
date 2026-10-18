package dev.bora.dispatch;

import java.time.Instant;
import java.util.Objects;

public record OfferTransition(
    OfferState previous, OfferState next, OfferCommand command, Instant occurredAt) {

  public OfferTransition {
    Objects.requireNonNull(previous, "O estado anterior é obrigatório.");
    Objects.requireNonNull(next, "O próximo estado é obrigatório.");
    Objects.requireNonNull(command, "O comando é obrigatório.");
    Objects.requireNonNull(occurredAt, "O horário é obrigatório.");
  }
}
