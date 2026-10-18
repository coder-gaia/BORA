package dev.bora.orders;

import java.util.Objects;
import java.util.UUID;

public sealed interface OrderCommand {

  record Assign(UUID courierId) implements OrderCommand {
    public Assign {
      Objects.requireNonNull(courierId, "O entregador é obrigatório.");
    }
  }

  record ArriveAtStore() implements OrderCommand {}

  record PickUp() implements OrderCommand {}

  record Deliver() implements OrderCommand {}

  record CancelByStore() implements OrderCommand {}

  record CancelByOperations() implements OrderCommand {}

  record Reassign() implements OrderCommand {}
}
