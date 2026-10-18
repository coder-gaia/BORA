package dev.bora.orders;

import dev.bora.orders.internal.OrderLifecycleService;
import dev.bora.routing.GeoPoint;
import dev.bora.shared.ApiException;
import dev.bora.stores.StoreView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderWorkflow {
  private static final String SELECT =
      """
      SELECT o.id, o.status, o.courier_id, o.visitor_context_id, o.version, o.created_at,
        o.assigned_at, o.at_store_at, o.picked_up_at, o.store_id, s.name store_name,
        s.service_area_id, (s.simulated AND o.visitor_context_id IS NULL) simulated,
        ST_Y(s.location::geometry) store_latitude, ST_X(s.location::geometry) store_longitude,
        ST_Y(o.dropoff::geometry) dropoff_latitude, ST_X(o.dropoff::geometry) dropoff_longitude
      FROM orders o JOIN stores s ON s.id = o.store_id
      """;
  private final JdbcClient jdbc;
  private final OrderLifecycleService lifecycle;
  private final OrderConfirmationCodes codes;

  public OrderWorkflow(
      JdbcClient jdbc, OrderLifecycleService lifecycle, OrderConfirmationCodes codes) {
    this.jdbc = jdbc;
    this.lifecycle = lifecycle;
    this.codes = codes;
  }

  public Optional<OrderWorkItem> find(UUID id) {
    return jdbc.sql(SELECT + " WHERE o.id=:id")
        .param("id", id)
        .query((row, index) -> read(row))
        .optional();
  }

  public Optional<OrderWorkItem> findActiveForCourier(UUID courierId) {
    return jdbc.sql(
            SELECT
                + " WHERE o.courier_id=:courier AND o.status IN ('ASSIGNED','AT_STORE','PICKED_UP')")
        .param("courier", courierId)
        .query((row, index) -> read(row))
        .optional();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public OrderWorkItem lockForUpdate(UUID id) {
    return jdbc.sql(SELECT + " WHERE o.id=:id FOR UPDATE OF o")
        .param("id", id)
        .query((row, index) -> read(row))
        .optional()
        .orElseThrow(
            () ->
                new ApiException(
                    HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Pedido não encontrado."));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<OrderWorkItem> tryLockForUpdate(UUID id) {
    return jdbc.sql(SELECT + " WHERE o.id=:id FOR UPDATE OF o SKIP LOCKED")
        .param("id", id)
        .query((row, index) -> read(row))
        .optional();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public OrderChange assign(UUID id, long version, UUID courierId, String actor) {
    return lifecycle.assign(id, version, courierId, actor);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public OrderChange arriveAtStore(UUID id, long version, String actor) {
    return lifecycle.arriveAtStore(id, version, actor);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public OrderChange pickUp(UUID id, long version, String actor) {
    return lifecycle.pickUp(id, version, actor);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public OrderChange deliver(UUID id, long version, String actor) {
    return lifecycle.deliver(id, version, actor);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public OrderChange reassign(UUID id, long version, String actor) {
    return lifecycle.reassign(id, version, actor);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean verifyConfirmation(UUID id, String code) {
    return jdbc.sql("SELECT confirmation_code_hash FROM orders WHERE id=:id")
        .param("id", id)
        .query(String.class)
        .optional()
        .map(hash -> codes.verify(id, code, hash))
        .orElse(false);
  }

  private static OrderWorkItem read(ResultSet row) throws SQLException {
    return new OrderWorkItem(
        row.getObject("id", UUID.class),
        new StoreView(
            row.getObject("store_id", UUID.class),
            row.getString("store_name"),
            new GeoPoint(row.getDouble("store_latitude"), row.getDouble("store_longitude")),
            row.getObject("service_area_id", UUID.class)),
        new GeoPoint(row.getDouble("dropoff_latitude"), row.getDouble("dropoff_longitude")),
        OrderStatus.valueOf(row.getString("status")),
        row.getObject("courier_id", UUID.class),
        row.getObject("visitor_context_id", UUID.class),
        row.getLong("version"),
        row.getBoolean("simulated"),
        instant(row, "created_at"),
        instant(row, "assigned_at"),
        instant(row, "at_store_at"),
        instant(row, "picked_up_at"));
  }

  private static Instant instant(ResultSet row, String name) throws SQLException {
    var value = row.getTimestamp(name);
    return value == null ? null : value.toInstant();
  }
}
