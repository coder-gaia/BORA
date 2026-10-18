package dev.bora.fulfillment.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "bora.fulfillment.reconciliation-enabled",
    havingValue = "true",
    matchIfMissing = true)
class FulfillmentReconciler {
  private final DeliveryProgress progress;

  FulfillmentReconciler(DeliveryProgress progress) {
    this.progress = progress;
  }

  @Scheduled(
      fixedDelayString = "${bora.fulfillment.reconcile-delay-ms:1000}",
      initialDelayString = "${bora.fulfillment.reconcile-delay-ms:1000}")
  void reconcile() {
    progress.reconcile();
  }
}
