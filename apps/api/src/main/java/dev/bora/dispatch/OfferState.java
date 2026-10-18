package dev.bora.dispatch;

import java.time.Instant;
import java.util.Objects;

public sealed interface OfferState {

  OfferStatus status();

  Instant expiresAt();

  static OfferState restore(OfferStatus status, Instant expiresAt) {
    Objects.requireNonNull(status, "O estado da oferta é obrigatório.");
    return switch (status) {
      case PENDING -> new Pending(expiresAt);
      case ACCEPTED -> new Accepted(expiresAt);
      case REJECTED -> new Rejected(expiresAt);
      case EXPIRED -> new Expired(expiresAt);
      case CANCELED -> new Canceled(expiresAt);
    };
  }

  default OfferTransition apply(OfferCommand command, Instant occurredAt) {
    Objects.requireNonNull(command, "O comando é obrigatório.");
    Objects.requireNonNull(occurredAt, "O horário é obrigatório.");
    if (!(this instanceof Pending)) {
      throw invalid(command);
    }
    OfferState next =
        switch (command) {
          case OfferCommand.Accept ignored -> {
            requireOpenDeadline(occurredAt);
            yield new Accepted(expiresAt());
          }
          case OfferCommand.Reject ignored -> {
            requireOpenDeadline(occurredAt);
            yield new Rejected(expiresAt());
          }
          case OfferCommand.Expire ignored -> {
            if (occurredAt.isBefore(expiresAt())) {
              throw new InvalidOfferTransition("O prazo da oferta ainda não terminou.");
            }
            yield new Expired(expiresAt());
          }
          case OfferCommand.Cancel ignored -> new Canceled(expiresAt());
        };
    return new OfferTransition(this, next, command, occurredAt);
  }

  private void requireOpenDeadline(Instant occurredAt) {
    if (!occurredAt.isBefore(expiresAt())) {
      throw new InvalidOfferTransition("O prazo da oferta terminou.");
    }
  }

  private InvalidOfferTransition invalid(OfferCommand command) {
    return new InvalidOfferTransition(
        "O comando "
            + command.getClass().getSimpleName()
            + " não é permitido em "
            + status()
            + ".");
  }

  record Pending(Instant expiresAt) implements OfferState {
    public Pending {
      Objects.requireNonNull(expiresAt, "O prazo da oferta é obrigatório.");
    }

    @Override
    public OfferStatus status() {
      return OfferStatus.PENDING;
    }
  }

  record Accepted(Instant expiresAt) implements OfferState {
    public Accepted {
      Objects.requireNonNull(expiresAt, "O prazo da oferta é obrigatório.");
    }

    @Override
    public OfferStatus status() {
      return OfferStatus.ACCEPTED;
    }
  }

  record Rejected(Instant expiresAt) implements OfferState {
    public Rejected {
      Objects.requireNonNull(expiresAt, "O prazo da oferta é obrigatório.");
    }

    @Override
    public OfferStatus status() {
      return OfferStatus.REJECTED;
    }
  }

  record Expired(Instant expiresAt) implements OfferState {
    public Expired {
      Objects.requireNonNull(expiresAt, "O prazo da oferta é obrigatório.");
    }

    @Override
    public OfferStatus status() {
      return OfferStatus.EXPIRED;
    }
  }

  record Canceled(Instant expiresAt) implements OfferState {
    public Canceled {
      Objects.requireNonNull(expiresAt, "O prazo da oferta é obrigatório.");
    }

    @Override
    public OfferStatus status() {
      return OfferStatus.CANCELED;
    }
  }
}
