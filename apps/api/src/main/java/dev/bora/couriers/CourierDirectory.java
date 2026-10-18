package dev.bora.couriers;

import dev.bora.routing.GeoPoint;
import dev.bora.shared.ApiException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourierDirectory {
  private static final String SELECT =
      """
      SELECT id,name,status,location_updated_at,version,
        ST_Y(location::geometry) latitude,ST_X(location::geometry) longitude
      FROM couriers WHERE id=:id
      """;
  private final JdbcClient jdbc;
  private final Clock clock;

  public CourierDirectory(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public CourierView find(UUID id) {
    return read(id, false);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public CourierView lock(UUID id) {
    return read(id, true);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public CourierView transition(UUID id, CourierCommand command) {
    CourierView courier = lock(id);
    if (courier.status() == CourierStatus.AVAILABLE
        && (command instanceof CourierCommand.CompleteDelivery
            || command instanceof CourierCommand.CancelOrder
            || command instanceof CourierCommand.ReassignOrder
            || command instanceof CourierCommand.CancelOffer
            || command instanceof CourierCommand.ExpireOffer
            || command instanceof CourierCommand.RejectOffer)) {
      return courier;
    }
    CourierTransition transition =
        CourierState.restore(courier.status()).apply(command, clock.instant());
    jdbc.sql("UPDATE couriers SET status=:status,version=version+1 WHERE id=:id")
        .param("id", id)
        .param("status", transition.next().status().name())
        .update();
    return find(id);
  }

  private CourierView read(UUID id, boolean lock) {
    return jdbc.sql(SELECT + (lock ? " FOR UPDATE" : ""))
        .param("id", id)
        .query(
            (row, index) -> {
              var recorded = row.getTimestamp("location_updated_at");
              return new CourierView(
                  row.getObject("id", UUID.class),
                  row.getString("name"),
                  CourierStatus.valueOf(row.getString("status")),
                  recorded == null
                      ? null
                      : new GeoPoint(row.getDouble("latitude"), row.getDouble("longitude")),
                  recorded == null ? null : recorded.toInstant(),
                  row.getLong("version"));
            })
        .optional()
        .orElseThrow(
            () ->
                new ApiException(
                    HttpStatus.NOT_FOUND, "COURIER_NOT_FOUND", "Entregador não encontrado."));
  }
}
