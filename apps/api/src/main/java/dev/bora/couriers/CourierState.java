package dev.bora.couriers;

import java.time.Instant;
import java.util.Objects;

public sealed interface CourierState {

  CourierStatus status();

  static CourierState restore(CourierStatus status) {
    Objects.requireNonNull(status, "O estado do entregador é obrigatório.");
    return switch (status) {
      case OFFLINE -> new Offline();
      case AVAILABLE -> new Available();
      case OFFERED -> new Offered();
      case BUSY -> new Busy();
    };
  }

  default CourierTransition apply(CourierCommand command, Instant occurredAt) {
    Objects.requireNonNull(command, "O comando é obrigatório.");
    Objects.requireNonNull(occurredAt, "O horário é obrigatório.");
    CourierState next =
        switch (this) {
          case Offline ignored ->
              switch (command) {
                case CourierCommand.GoOnline ignoredCommand -> new Available();
                default -> throw invalid(command);
              };
          case Available ignored ->
              switch (command) {
                case CourierCommand.GoOffline ignoredCommand -> new Offline();
                case CourierCommand.ReceiveOffer ignoredCommand -> new Offered();
                default -> throw invalid(command);
              };
          case Offered ignored ->
              switch (command) {
                case CourierCommand.AcceptOffer ignoredCommand -> new Busy();
                case CourierCommand.RejectOffer ignoredCommand -> new Available();
                case CourierCommand.ExpireOffer ignoredCommand -> new Available();
                case CourierCommand.CancelOffer ignoredCommand -> new Available();
                default -> throw invalid(command);
              };
          case Busy ignored ->
              switch (command) {
                case CourierCommand.CompleteDelivery ignoredCommand -> new Available();
                case CourierCommand.CancelOrder ignoredCommand -> new Available();
                case CourierCommand.ReassignOrder ignoredCommand -> new Available();
                default -> throw invalid(command);
              };
        };
    return new CourierTransition(this, next, command, occurredAt);
  }

  private InvalidCourierTransition invalid(CourierCommand command) {
    return new InvalidCourierTransition(
        "O comando "
            + command.getClass().getSimpleName()
            + " não é permitido em "
            + status()
            + ".");
  }

  record Offline() implements CourierState {
    @Override
    public CourierStatus status() {
      return CourierStatus.OFFLINE;
    }
  }

  record Available() implements CourierState {
    @Override
    public CourierStatus status() {
      return CourierStatus.AVAILABLE;
    }
  }

  record Offered() implements CourierState {
    @Override
    public CourierStatus status() {
      return CourierStatus.OFFERED;
    }
  }

  record Busy() implements CourierState {
    @Override
    public CourierStatus status() {
      return CourierStatus.BUSY;
    }
  }
}
