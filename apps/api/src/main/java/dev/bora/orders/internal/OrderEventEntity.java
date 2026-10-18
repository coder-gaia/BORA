package dev.bora.orders.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "order_events")
class OrderEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_id", nullable = false)
  private UUID orderId;

  @Column(nullable = false, length = 64)
  private String type;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> payload;

  @Column(nullable = false, length = 160)
  private String actor;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "order_version", nullable = false)
  private long orderVersion;

  protected OrderEventEntity() {}

  OrderEventEntity(
      UUID orderId,
      String type,
      Map<String, Object> payload,
      String actor,
      Instant occurredAt,
      long orderVersion) {
    this.orderId = orderId;
    this.type = type;
    this.payload = Map.copyOf(payload);
    this.actor = actor;
    this.occurredAt = occurredAt;
    this.orderVersion = orderVersion;
  }
}
