package dev.bora.orders.internal;

import dev.bora.orders.OrderCommand;
import dev.bora.orders.OrderState;
import dev.bora.orders.OrderStatus;
import dev.bora.orders.OrderTransition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "orders")
class OrderEntity {

  @Id private UUID id;

  @Column(name = "public_code", nullable = false, length = 64)
  private String publicCode;

  @Column(name = "store_id", nullable = false)
  private UUID storeId;

  @JdbcTypeCode(SqlTypes.GEOGRAPHY)
  @Column(nullable = false, columnDefinition = "geography(Point,4326)")
  private Point dropoff;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private OrderStatus status;

  @Column(name = "courier_id")
  private UUID courierId;

  @Column(name = "confirmation_code_hash")
  private String confirmationCodeHash;

  @Column(name = "sla_deadline", nullable = false)
  private Instant slaDeadline;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "search_radius_m", nullable = false)
  private int searchRadiusM;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "assigned_at")
  private Instant assignedAt;

  @Column(name = "at_store_at")
  private Instant atStoreAt;

  @Column(name = "picked_up_at")
  private Instant pickedUpAt;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  @Column(name = "canceled_at")
  private Instant canceledAt;

  @Version
  @Column(nullable = false)
  private long version;

  @Column(name = "visitor_context_id")
  private UUID visitorContextId;

  @Column(name = "owner_user_id")
  private UUID ownerUserId;

  protected OrderEntity() {}

  OrderEntity(
      UUID id,
      String publicCode,
      UUID storeId,
      Point dropoff,
      String confirmationCodeHash,
      Instant now,
      Instant slaDeadline,
      UUID visitorContextId,
      UUID ownerUserId) {
    this.id = id;
    this.publicCode = publicCode;
    this.storeId = storeId;
    this.dropoff = (Point) dropoff.copy();
    this.confirmationCodeHash = confirmationCodeHash;
    this.createdAt = now;
    this.slaDeadline = slaDeadline;
    this.status = OrderStatus.SEARCHING;
    this.nextAttemptAt = now;
    this.searchRadiusM = 2_000;
    this.visitorContextId = visitorContextId;
    this.ownerUserId = ownerUserId;
  }

  UUID id() {
    return id;
  }

  long version() {
    return version;
  }

  OrderState state() {
    return OrderState.restore(status, courierId);
  }

  Point dropoff() {
    return (Point) dropoff.copy();
  }

  void apply(OrderTransition transition) {
    Instant occurredAt = transition.occurredAt();
    if (occurredAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("A transição não pode preceder a criação do pedido.");
    }
    switch (transition.command()) {
      case OrderCommand.Assign ignored -> {
        assignedAt = occurredAt;
        atStoreAt = null;
        pickedUpAt = null;
        deliveredAt = null;
        canceledAt = null;
        nextAttemptAt = null;
      }
      case OrderCommand.ArriveAtStore ignored -> atStoreAt = occurredAt;
      case OrderCommand.PickUp ignored -> pickedUpAt = occurredAt;
      case OrderCommand.Deliver ignored -> deliveredAt = occurredAt;
      case OrderCommand.CancelByStore ignored -> {
        canceledAt = occurredAt;
        nextAttemptAt = null;
      }
      case OrderCommand.CancelByOperations ignored -> {
        canceledAt = occurredAt;
        nextAttemptAt = null;
      }
      case OrderCommand.Reassign ignored -> {
        assignedAt = null;
        atStoreAt = null;
        pickedUpAt = null;
        deliveredAt = null;
        canceledAt = null;
        nextAttemptAt = occurredAt;
        searchRadiusM = 2_000;
      }
    }
    status = transition.next().status();
    courierId = transition.next().courierId();
  }
}
