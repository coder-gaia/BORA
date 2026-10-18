package dev.bora.fulfillment.internal;

import dev.bora.couriers.CourierCommand;
import dev.bora.couriers.CourierDirectory;
import dev.bora.couriers.CourierStatus;
import dev.bora.dispatch.PendingOfferCleanup;
import dev.bora.orders.OrderResourcesReleased;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class OrderResourceReleaseListener {
  private final CourierDirectory couriers;
  private final DeliveryProgress progress;
  private final PendingOfferCleanup offers;

  OrderResourceReleaseListener(
      CourierDirectory couriers, DeliveryProgress progress, PendingOfferCleanup offers) {
    this.couriers = couriers;
    this.progress = progress;
    this.offers = offers;
  }

  @EventListener
  @Transactional(propagation = Propagation.MANDATORY)
  public void release(OrderResourcesReleased event) {
    offers.cancelPendingForLockedOrder(event.orderId(), event.occurredAt());
    if (event.previousCourierId() == null) return;
    var courier = couriers.lock(event.previousCourierId());
    progress.recordFinalPoint(event, courier);
    // Fixtures de domínio e uma repetição após liberação podem já estar disponíveis.
    if (courier.status() == CourierStatus.AVAILABLE || courier.status() == CourierStatus.OFFLINE)
      return;
    CourierCommand command =
        switch (event.reason()) {
          case CANCELED -> new CourierCommand.CancelOrder();
          case REASSIGNED -> new CourierCommand.ReassignOrder();
          case DELIVERED -> new CourierCommand.CompleteDelivery();
        };
    couriers.transition(courier.id(), command);
  }
}
