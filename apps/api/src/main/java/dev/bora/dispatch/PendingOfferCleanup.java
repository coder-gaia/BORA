package dev.bora.dispatch;

import dev.bora.couriers.CourierCommand;
import dev.bora.couriers.CourierDirectory;
import dev.bora.couriers.CourierStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PendingOfferCleanup {
  private final JdbcClient jdbc;
  private final CourierDirectory couriers;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public PendingOfferCleanup(
      JdbcClient jdbc, CourierDirectory couriers, Clock clock, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.couriers = couriers;
    this.clock = clock;
    this.transaction = new TransactionTemplate(manager);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void cancelPendingForLockedOrder(UUID orderId, Instant occurredAt) {
    closePending(orderId, new OfferCommand.Cancel(), occurredAt);
  }

  public int expireDueOffers() {
    int expired = 0;
    for (int attempt = 0; attempt < 20; attempt++) {
      boolean closed =
          Boolean.TRUE.equals(
              transaction.execute(
                  status -> {
                    var due =
                        jdbc.sql(
                                """
            SELECT o.id FROM orders o JOIN offers f ON f.order_id = o.id
            WHERE f.status = 'PENDING' AND f.expires_at <= :now
            ORDER BY f.expires_at, f.id LIMIT 1
            FOR UPDATE OF o SKIP LOCKED
            """)
                            .param("now", timestamp(clock.instant()))
                            .query(UUID.class)
                            .optional();
                    if (due.isEmpty()) return false;
                    UUID orderId = due.get();
                    Instant now = clock.instant();
                    return expirePendingForLockedOrder(orderId, now);
                  }));
      if (!closed) break;
      expired++;
    }
    return expired;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean expirePendingForLockedOrder(UUID orderId, Instant now) {
    if (!closePending(orderId, new OfferCommand.Expire(), now)) return false;
    jdbc.sql(
            """
        UPDATE orders SET next_attempt_at = GREATEST(created_at, :now)
        WHERE id = :id AND status = 'SEARCHING'
        """)
        .param("id", orderId)
        .param("now", timestamp(now))
        .update();
    return true;
  }

  private boolean closePending(UUID orderId, OfferCommand command, Instant occurredAt) {
    var pending =
        jdbc.sql(
                """
        SELECT id, courier_id, expires_at, created_at FROM offers
        WHERE order_id = :order AND status = 'PENDING' FOR UPDATE
        """)
            .param("order", orderId)
            .query(
                (row, index) ->
                    new PendingOffer(
                        row.getObject("id", UUID.class), row.getObject("courier_id", UUID.class),
                        row.getTimestamp("expires_at").toInstant(),
                            row.getTimestamp("created_at").toInstant()))
            .optional();
    if (pending.isEmpty()) return false;
    PendingOffer offer = pending.get();
    if (command instanceof OfferCommand.Expire && occurredAt.isBefore(offer.expiresAt()))
      return false;
    Instant response = occurredAt.isBefore(offer.createdAt()) ? offer.createdAt() : occurredAt;
    OfferTransition transition =
        OfferState.restore(OfferStatus.PENDING, offer.expiresAt()).apply(command, response);
    var courier = couriers.lock(offer.courierId());
    jdbc.sql(
            """
        UPDATE offers SET status = :status, responded_at = :now
        WHERE id = :id AND status = 'PENDING'
        """)
        .param("id", offer.id())
        .param("status", transition.next().status().name())
        .param("now", timestamp(response))
        .update();
    // Uma reserva antiga nunca libera um entregador que já está em outra entrega.
    if (courier.status() == CourierStatus.OFFERED) {
      couriers.transition(
          courier.id(),
          command instanceof OfferCommand.Expire
              ? new CourierCommand.ExpireOffer()
              : new CourierCommand.CancelOffer());
    }
    jdbc.sql(
            """
        INSERT INTO order_events(order_id,type,payload,actor,occurred_at,order_version)
        SELECT id,:type,jsonb_build_object('from',status,'to',status,
          'offerId',CAST(:offer AS text),'courierId',CAST(:courier AS text),
          'fromOfferStatus','PENDING','toOfferStatus',CAST(:status AS text)),
          'dispatch',:now,version FROM orders WHERE id=:order
        """)
        .param("order", orderId)
        .param("type", "OFFER_" + transition.next().status().name())
        .param("offer", offer.id().toString())
        .param("courier", offer.courierId().toString())
        .param("status", transition.next().status().name())
        .param("now", timestamp(response))
        .update();
    return true;
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private record PendingOffer(UUID id, UUID courierId, Instant expiresAt, Instant createdAt) {}
}
