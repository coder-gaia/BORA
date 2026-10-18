package dev.bora.fulfillment.internal;

import dev.bora.couriers.CourierDirectory;
import dev.bora.couriers.CourierPositionsPersisted;
import dev.bora.couriers.CourierView;
import dev.bora.couriers.PositionSample;
import dev.bora.orders.OrderResourcesReleased;
import dev.bora.orders.OrderStatus;
import dev.bora.orders.OrderWorkItem;
import dev.bora.orders.OrderWorkflow;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class DeliveryProgress {
  private static final Logger LOG = LoggerFactory.getLogger(DeliveryProgress.class);
  private final JdbcClient jdbc;
  private final JdbcTemplate batches;
  private final CourierDirectory couriers;
  private final OrderWorkflow orders;
  private final Clock clock;
  private final TransactionTemplate transaction;

  DeliveryProgress(
      JdbcClient jdbc,
      JdbcTemplate batches,
      CourierDirectory couriers,
      OrderWorkflow orders,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.batches = batches;
    this.couriers = couriers;
    this.orders = orders;
    this.clock = clock;
    this.transaction = new TransactionTemplate(manager);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @EventListener
  public void positionsPersisted(CourierPositionsPersisted event) {
    if (event.positions().isEmpty()) return;
    var active =
        jdbc.sql(
                """
        SELECT id,courier_id FROM orders WHERE courier_id IN (:couriers)
          AND status IN ('ASSIGNED','AT_STORE','PICKED_UP')
        """)
            .param("couriers", event.positions().keySet())
            .query(
                (row, index) ->
                    new ActiveOrder(
                        row.getObject("id", UUID.class), row.getObject("courier_id", UUID.class)))
            .list();
    for (var order : active) {
      advance(
          order.id(),
          order.courierId(),
          event.positions().getOrDefault(order.courierId(), List.of()),
          event.epochs().get(order.courierId()));
    }
  }

  public void reconcile() {
    var pending =
        jdbc.sql(
                """
        SELECT o.id,o.courier_id FROM orders o JOIN couriers c ON c.id=o.courier_id
        JOIN stores s ON s.id=o.store_id
        WHERE o.status IN ('ASSIGNED','AT_STORE','PICKED_UP')
          AND c.location_updated_at>=o.assigned_at
          AND (NOT EXISTS(SELECT 1 FROM delivery_tracks t
                 WHERE t.order_id=o.id AND t.recorded_at=c.location_updated_at)
            OR (o.status='ASSIGNED' AND c.location_updated_at>=:fresh
                 AND ST_DWithin(c.location,s.location,60)))
        ORDER BY o.id
        """)
            .param(
                "fresh", OffsetDateTime.ofInstant(clock.instant().minusSeconds(30), ZoneOffset.UTC))
            .query(
                (row, index) ->
                    new ActiveOrder(
                        row.getObject("id", UUID.class), row.getObject("courier_id", UUID.class)))
            .list();
    for (var order : pending) advance(order.id(), order.courierId(), List.of(), null);
  }

  private void advance(UUID id, UUID courierId, List<PositionSample> received, Long expectedEpoch) {
    try {
      transaction.executeWithoutResult(
          status -> {
            var locked = orders.tryLockForUpdate(id);
            if (locked.isEmpty()) return;
            OrderWorkItem order = locked.get();
            if (!FulfillmentCoordinator.active(order.status())
                || !courierId.equals(order.courierId())) return;
            CourierView courier = couriers.lock(courierId);
            if (expectedEpoch != null
                && !expectedEpoch.equals(
                    jdbc.sql("SELECT position_epoch FROM couriers WHERE id=:id")
                        .param("id", courierId)
                        .query(Long.class)
                        .single())) return;
            List<PositionSample> points = new ArrayList<>(received);
            if (courier.location() != null && courier.locationUpdatedAt() != null) {
              points.add(new PositionSample(courier.locationUpdatedAt(), courier.location()));
            }
            record(order, points);
            if (order.status() == OrderStatus.ASSIGNED
                && FulfillmentCoordinator.fresh(courier, order, clock.instant())
                && jdbc.sql(
                        """
                SELECT ST_DWithin(c.location,s.location,60)
                FROM couriers c CROSS JOIN stores s WHERE c.id=:courier AND s.id=:store
                """)
                    .param("courier", courierId)
                    .param("store", order.store().id())
                    .query(Boolean.class)
                    .single()) {
              orders.arriveAtStore(id, order.version(), "system:arrival");
            }
          });
    } catch (RuntimeException failure) {
      LOG.warn(
          "Não foi possível atualizar o progresso do pedido {}. A reconciliação tentará novamente.",
          id,
          failure);
    }
  }

  void recordFinalPoint(OrderResourcesReleased event, CourierView courier) {
    if (event.reason() != OrderResourcesReleased.Reason.DELIVERED
        || courier.location() == null
        || courier.locationUpdatedAt() == null
        || courier.locationUpdatedAt().isAfter(event.occurredAt())) return;
    orders
        .find(event.orderId())
        .ifPresent(
            order ->
                record(
                    order,
                    List.of(new PositionSample(courier.locationUpdatedAt(), courier.location()))));
  }

  private void record(OrderWorkItem order, List<PositionSample> points) {
    var valid =
        points.stream()
            .filter(
                point ->
                    order.assignedAt() != null && !point.recordedAt().isBefore(order.assignedAt()))
            .toList();
    if (valid.isEmpty()) return;
    batches.batchUpdate(
        """
        INSERT INTO delivery_tracks(order_id,recorded_at,position)
        VALUES(?,?,ST_SetSRID(ST_MakePoint(?,?),4326)::geography)
        ON CONFLICT(order_id,recorded_at) DO NOTHING
        """,
        valid,
        64,
        (statement, point) -> {
          statement.setObject(1, order.id());
          statement.setObject(2, OffsetDateTime.ofInstant(point.recordedAt(), ZoneOffset.UTC));
          statement.setDouble(3, point.position().longitude());
          statement.setDouble(4, point.position().latitude());
        });
  }

  private record ActiveOrder(UUID id, UUID courierId) {}
}
