package dev.bora.orders;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public sealed interface OrderState {

  OrderStatus status();

  default UUID courierId() {
    return null;
  }

  static OrderState restore(OrderStatus status, UUID courierId) {
    Objects.requireNonNull(status, "O estado do pedido é obrigatório.");
    return switch (status) {
      case SEARCHING -> {
        if (courierId != null) {
          throw new IllegalArgumentException("Um pedido em busca não pode ter entregador.");
        }
        yield new Searching();
      }
      case ASSIGNED -> new Assigned(courierId);
      case AT_STORE -> new AtStore(courierId);
      case PICKED_UP -> new PickedUp(courierId);
      case DELIVERED -> new Delivered(courierId);
      case CANCELED -> new Canceled(courierId);
    };
  }

  default OrderTransition apply(OrderCommand command, Instant occurredAt) {
    Objects.requireNonNull(command, "O comando é obrigatório.");
    Objects.requireNonNull(occurredAt, "O horário é obrigatório.");
    OrderState next =
        switch (this) {
          case Searching ignored ->
              switch (command) {
                case OrderCommand.Assign assign -> new Assigned(assign.courierId());
                case OrderCommand.CancelByStore ignoredCommand -> new Canceled(null);
                case OrderCommand.CancelByOperations ignoredCommand -> new Canceled(null);
                default -> throw invalid(command);
              };
          case Assigned assigned ->
              switch (command) {
                case OrderCommand.ArriveAtStore ignoredCommand -> new AtStore(assigned.courierId());
                case OrderCommand.CancelByStore ignoredCommand ->
                    new Canceled(assigned.courierId());
                case OrderCommand.CancelByOperations ignoredCommand ->
                    new Canceled(assigned.courierId());
                case OrderCommand.Reassign ignoredCommand -> new Searching();
                default -> throw invalid(command);
              };
          case AtStore atStore ->
              switch (command) {
                case OrderCommand.PickUp ignoredCommand -> new PickedUp(atStore.courierId());
                case OrderCommand.CancelByOperations ignoredCommand ->
                    new Canceled(atStore.courierId());
                case OrderCommand.Reassign ignoredCommand -> new Searching();
                default -> throw invalid(command);
              };
          case PickedUp pickedUp ->
              switch (command) {
                case OrderCommand.Deliver ignoredCommand -> new Delivered(pickedUp.courierId());
                default -> throw invalid(command);
              };
          case Delivered ignored -> throw invalid(command);
          case Canceled ignored -> throw invalid(command);
        };
    return new OrderTransition(this, next, command, occurredAt);
  }

  private InvalidOrderTransition invalid(OrderCommand command) {
    return new InvalidOrderTransition(
        "O comando "
            + command.getClass().getSimpleName()
            + " não é permitido em "
            + status()
            + ".");
  }

  private static void requireCourier(UUID courierId) {
    if (courierId == null) {
      throw new IllegalArgumentException("O estado do pedido exige um entregador.");
    }
  }

  record Searching() implements OrderState {
    @Override
    public OrderStatus status() {
      return OrderStatus.SEARCHING;
    }
  }

  record Assigned(UUID courierId) implements OrderState {
    public Assigned {
      requireCourier(courierId);
    }

    @Override
    public OrderStatus status() {
      return OrderStatus.ASSIGNED;
    }
  }

  record AtStore(UUID courierId) implements OrderState {
    public AtStore {
      requireCourier(courierId);
    }

    @Override
    public OrderStatus status() {
      return OrderStatus.AT_STORE;
    }
  }

  record PickedUp(UUID courierId) implements OrderState {
    public PickedUp {
      requireCourier(courierId);
    }

    @Override
    public OrderStatus status() {
      return OrderStatus.PICKED_UP;
    }
  }

  record Delivered(UUID courierId) implements OrderState {
    public Delivered {
      requireCourier(courierId);
    }

    @Override
    public OrderStatus status() {
      return OrderStatus.DELIVERED;
    }
  }

  record Canceled(UUID courierId) implements OrderState {
    @Override
    public OrderStatus status() {
      return OrderStatus.CANCELED;
    }
  }
}
