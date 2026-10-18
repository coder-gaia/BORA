package dev.bora.fulfillment.internal;

import dev.bora.couriers.CourierDirectory;
import dev.bora.couriers.CourierView;
import dev.bora.demo.DemoLimits;
import dev.bora.fulfillment.AssignedOrderView;
import dev.bora.fulfillment.CourierRouteView;
import dev.bora.fulfillment.CourierWorkView;
import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.orders.InvalidOrderTransition;
import dev.bora.orders.OrderStatus;
import dev.bora.orders.OrderVersionConflict;
import dev.bora.orders.OrderWorkItem;
import dev.bora.orders.OrderWorkflow;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingService;
import dev.bora.shared.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class FulfillmentCoordinator {
  private final CourierDirectory couriers;
  private final OrderWorkflow orders;
  private final RoutingService routing;
  private final DemoLimits limits;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final TransactionTemplate readTransaction;

  FulfillmentCoordinator(
      CourierDirectory couriers,
      OrderWorkflow orders,
      RoutingService routing,
      DemoLimits limits,
      Clock clock,
      PlatformTransactionManager manager) {
    this.couriers = couriers;
    this.orders = orders;
    this.routing = routing;
    this.limits = limits;
    this.clock = clock;
    this.transaction = new TransactionTemplate(manager);
    this.readTransaction = new TransactionTemplate(manager);
    this.readTransaction.setReadOnly(true);
    this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  CourierWorkView work(DemoPrincipal user) {
    requireCourier(user);
    return readTransaction.execute(
        status ->
            new CourierWorkView(
                couriers.find(user.courierId()),
                orders
                    .findActiveForCourier(user.courierId())
                    .map(FulfillmentCoordinator::view)
                    .orElse(null)));
  }

  CourierRouteView route(DemoPrincipal user) {
    requireCourier(user);
    RouteContext context =
        readTransaction.execute(
            status ->
                new RouteContext(
                    orders
                        .findActiveForCourier(user.courierId())
                        .orElseThrow(
                            () ->
                                failure(
                                    HttpStatus.CONFLICT,
                                    "NO_ACTIVE_ORDER",
                                    "Você ainda não tem uma entrega ativa.")),
                    couriers.find(user.courierId())));
    OrderWorkItem order = context.order();
    CourierView courier = context.courier();
    if (!fresh(courier, order, clock.instant())) {
      throw failure(
          HttpStatus.CONFLICT,
          "COURIER_POSITION_STALE",
          "Envie uma posição recente para consultar a rota.");
    }
    boolean dropoff = order.status() == OrderStatus.PICKED_UP;
    var target = dropoff ? order.dropoff() : order.store().location();
    try {
      var route = routing.route(courier.location(), target);
      return new CourierRouteView(
          order.id(),
          dropoff ? CourierRouteView.Destination.DROPOFF : CourierRouteView.Destination.PICKUP,
          target,
          route.geometry(),
          route.distanceMeters(),
          route.duration().toMillis(),
          route.simulatedDuration().toMillis());
    } catch (RoutingException exception) {
      if (exception.reason() == RoutingException.Reason.GRAPH_UNAVAILABLE) {
        throw failure(
            HttpStatus.SERVICE_UNAVAILABLE,
            "ROUTING_UNAVAILABLE",
            "O roteamento ainda está sendo preparado. Tente novamente em instantes.");
      }
      throw failure(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "COURIER_ROUTE_UNAVAILABLE",
          "Não encontramos uma rota de moto para a próxima etapa.");
    }
  }

  AssignedOrderView pickUp(DemoPrincipal user, UUID id, long expectedVersion) {
    requireCourier(user);
    return mutate(
        () ->
            transaction.execute(
                status -> {
                  OrderWorkItem order = ownedLocked(user, id, expectedVersion);
                  couriers.lock(user.courierId());
                  orders.pickUp(id, order.version(), "user:" + user.userId());
                  return view(orders.find(id).orElseThrow());
                }));
  }

  AssignedOrderView deliver(
      DemoPrincipal user, UUID id, long expectedVersion, String confirmationCode) {
    requireCourier(user);
    // O limitador termina antes de qualquer lock ou conexão transacional do pedido.
    limits.checkDeliveryConfirmation(user.courierId());
    return mutate(
        () ->
            transaction.execute(
                status -> {
                  OrderWorkItem order = ownedLocked(user, id, expectedVersion);
                  couriers.lock(user.courierId());
                  if (order.status() != OrderStatus.PICKED_UP) {
                    throw failure(
                        HttpStatus.CONFLICT,
                        "INVALID_ORDER_TRANSITION",
                        "Confirme a coleta antes de concluir a entrega.");
                  }
                  if (!order.simulated() && !orders.verifyConfirmation(id, confirmationCode)) {
                    throw failure(
                        HttpStatus.CONFLICT,
                        "INVALID_CONFIRMATION_CODE",
                        "O código de confirmação está incorreto. Confira os seis números com a loja ou o cliente.");
                  }
                  orders.deliver(id, order.version(), "user:" + user.userId());
                  return view(orders.find(id).orElseThrow());
                }));
  }

  private OrderWorkItem ownedLocked(DemoPrincipal user, UUID id, long expectedVersion) {
    OrderWorkItem order = orders.lockForUpdate(id);
    if (!user.courierId().equals(order.courierId())) {
      throw failure(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Pedido não encontrado.");
    }
    if (order.version() != expectedVersion) {
      throw failure(
          HttpStatus.CONFLICT,
          "ORDER_VERSION_CONFLICT",
          "O pedido foi atualizado. Consulte a versão atual e tente novamente.");
    }
    return order;
  }

  static AssignedOrderView view(OrderWorkItem order) {
    return new AssignedOrderView(
        order.id(),
        order.status(),
        order.version(),
        order.store(),
        order.dropoff(),
        order.assignedAt(),
        order.atStoreAt(),
        order.pickedUpAt(),
        !order.simulated());
  }

  static boolean active(OrderStatus status) {
    return status == OrderStatus.ASSIGNED
        || status == OrderStatus.AT_STORE
        || status == OrderStatus.PICKED_UP;
  }

  static boolean fresh(CourierView courier, OrderWorkItem order, Instant now) {
    Instant at = courier.locationUpdatedAt();
    return courier.location() != null
        && at != null
        && order.assignedAt() != null
        && !at.isBefore(order.assignedAt())
        && !at.isBefore(now.minusSeconds(30))
        && !at.isAfter(now.plusSeconds(5));
  }

  private static AssignedOrderView mutate(java.util.function.Supplier<AssignedOrderView> action) {
    try {
      return action.get();
    } catch (OrderVersionConflict exception) {
      throw failure(
          HttpStatus.CONFLICT,
          "ORDER_VERSION_CONFLICT",
          "O pedido foi atualizado. Consulte a versão atual e tente novamente.");
    } catch (InvalidOrderTransition exception) {
      throw failure(
          HttpStatus.CONFLICT,
          "INVALID_ORDER_TRANSITION",
          "O pedido não permite essa ação nesta etapa.");
    }
  }

  private static void requireCourier(DemoPrincipal user) {
    if (user.role() != DemoRole.COURIER || user.courierId() == null) {
      throw failure(
          HttpStatus.FORBIDDEN,
          "COURIER_ROLE_REQUIRED",
          "Entre como entregador para consultar ou concluir uma entrega.");
    }
  }

  private record RouteContext(OrderWorkItem order, CourierView courier) {}

  private static ApiException failure(HttpStatus status, String code, String message) {
    return new ApiException(status, code, message);
  }
}
