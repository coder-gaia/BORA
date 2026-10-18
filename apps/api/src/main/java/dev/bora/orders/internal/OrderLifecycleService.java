package dev.bora.orders.internal;

import dev.bora.orders.OrderChange;
import dev.bora.orders.OrderCommand;
import dev.bora.orders.OrderTransition;
import dev.bora.orders.OrderVersionConflict;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderLifecycleService {

  private final EntityManager entityManager;
  private final Clock clock;

  OrderLifecycleService(EntityManager entityManager, Clock clock) {
    this.entityManager = entityManager;
    this.clock = clock;
  }

  public OrderChange assign(UUID id, long expectedVersion, UUID courierId, String actor) {
    return transition(id, expectedVersion, new OrderCommand.Assign(courierId), actor);
  }

  public OrderChange arriveAtStore(UUID id, long expectedVersion, String actor) {
    return transition(id, expectedVersion, new OrderCommand.ArriveAtStore(), actor);
  }

  public OrderChange pickUp(UUID id, long expectedVersion, String actor) {
    return transition(id, expectedVersion, new OrderCommand.PickUp(), actor);
  }

  public OrderChange deliver(UUID id, long expectedVersion, String actor) {
    return transition(id, expectedVersion, new OrderCommand.Deliver(), actor);
  }

  public OrderChange cancelByStore(UUID id, long expectedVersion, String actor) {
    return transition(id, expectedVersion, new OrderCommand.CancelByStore(), actor);
  }

  public OrderChange cancelByOperations(UUID id, long expectedVersion, String actor) {
    return transition(id, expectedVersion, new OrderCommand.CancelByOperations(), actor);
  }

  public OrderChange reassign(UUID id, long expectedVersion, String actor) {
    return transition(id, expectedVersion, new OrderCommand.Reassign(), actor);
  }

  private OrderChange transition(
      UUID id, long expectedVersion, OrderCommand command, String actor) {
    Objects.requireNonNull(id, "Informe o pedido.");
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("A versão do pedido não pode ser negativa.");
    }
    if (actor == null || actor.isBlank() || actor.strip().length() > 160) {
      throw new IllegalArgumentException("Informe um responsável de até 160 caracteres.");
    }
    OrderEntity order = entityManager.find(OrderEntity.class, id);
    if (order == null) {
      throw new IllegalArgumentException("Pedido não encontrado.");
    }
    if (order.version() != expectedVersion) {
      throw new OrderVersionConflict(id);
    }
    OrderTransition transition = order.state().apply(command, clock.instant());
    order.apply(transition);
    try {
      entityManager.flush();
    } catch (OptimisticLockException conflict) {
      throw new OrderVersionConflict(id, conflict);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("from", transition.previous().status().name());
    payload.put("to", transition.next().status().name());
    payload.put("command", command.getClass().getSimpleName());
    payload.put("version", order.version());
    if (transition.previous().courierId() != null) {
      payload.put("previousCourierId", transition.previous().courierId().toString());
    }
    if (transition.next().courierId() != null) {
      payload.put("courierId", transition.next().courierId().toString());
    }
    String eventType =
        command instanceof OrderCommand.Reassign
            ? "ORDER_REASSIGNED"
            : "ORDER_" + transition.next().status().name();
    entityManager.persist(
        new OrderEventEntity(
            id, eventType, payload, actor.strip(), transition.occurredAt(), order.version()));
    entityManager.flush();
    return new OrderChange(
        id,
        transition.next().status(),
        transition.next().courierId(),
        order.version(),
        transition.occurredAt());
  }
}
