package dev.bora.dispatch.internal;

import dev.bora.dispatch.DispatchCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "bora.dispatch.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
class DispatchWorker {
  private final DispatchCoordinator dispatch;

  DispatchWorker(DispatchCoordinator dispatch) {
    this.dispatch = dispatch;
  }

  @Scheduled(
      fixedDelayString = "${bora.dispatch.delay-ms:500}",
      initialDelayString = "${bora.dispatch.delay-ms:500}")
  void dispatch() {
    dispatch.dispatchAvailableOrders();
  }
}
