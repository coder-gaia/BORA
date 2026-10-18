package dev.bora.dispatch.internal;

import dev.bora.dispatch.PendingOfferCleanup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "bora.dispatch.expiry-enabled",
    havingValue = "true",
    matchIfMissing = true)
class PendingOfferExpiry {
  private final PendingOfferCleanup cleanup;

  PendingOfferExpiry(PendingOfferCleanup cleanup) {
    this.cleanup = cleanup;
  }

  @Scheduled(
      fixedDelayString = "${bora.dispatch.expiry-delay-ms:1000}",
      initialDelayString = "${bora.dispatch.expiry-delay-ms:1000}")
  void expire() {
    cleanup.expireDueOffers();
  }
}
