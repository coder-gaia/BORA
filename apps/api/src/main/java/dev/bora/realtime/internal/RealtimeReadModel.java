package dev.bora.realtime.internal;

import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.orders.OrderStatus;
import dev.bora.realtime.MapFrame;
import dev.bora.realtime.MapViewport;
import dev.bora.realtime.TrackingSnapshot;
import dev.bora.routing.GeoPoint;
import dev.bora.shared.ApiException;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class RealtimeReadModel {
  private final JdbcClient jdbc;
  private final Clock clock;

  RealtimeReadModel(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  boolean active(DemoPrincipal principal) {
    return jdbc.sql(
            """
        SELECT EXISTS(SELECT 1 FROM users u JOIN demo_contexts d ON d.id=u.context_id
          WHERE u.id=:id AND u.context_id=:context AND u.role=:role
            AND u.store_id IS NOT DISTINCT FROM CAST(:store AS uuid)
            AND u.courier_id IS NOT DISTINCT FROM CAST(:courier AS uuid)
            AND u.visitor=:visitor AND u.expires_at>clock_timestamp()
            AND d.expires_at>d.created_at AND d.expires_at>clock_timestamp())
        """)
        .param("id", principal.userId())
        .param("context", principal.contextId())
        .param("role", principal.role().name())
        .param("store", principal.storeId())
        .param("courier", principal.courierId())
        .param("visitor", principal.visitor())
        .query(Boolean.class)
        .single();
  }

  MapFrame map(MapViewport viewport, DemoPrincipal principal) {
    boolean operations = viewport.scope() == MapViewport.Scope.OPERATIONS;
    if (operations
        && (principal == null || principal.role() != DemoRole.OPERATIONS || !active(principal))) {
      throw new ApiException(
          HttpStatus.FORBIDDEN, "FORBIDDEN", "Entre na operação para consultar este mapa.");
    }
    List<List<Object>> points =
        jdbc.sql(
                """
        SELECT c.id::text, ST_X(c.location::geometry) longitude,
          ST_Y(c.location::geometry) latitude, c.status
        FROM couriers c WHERE c.status<>'OFFLINE' AND c.location IS NOT NULL
          AND c.location_updated_at>=clock_timestamp()-interval '30 seconds'
          AND c.location && ST_MakeEnvelope(:west,:south,:east,:north,4326)::geography
          AND ((c.simulated AND NOT EXISTS(
              SELECT 1 FROM offers f JOIN orders private ON private.id=f.order_id
              WHERE f.courier_id=c.id AND f.status='PENDING' AND private.visitor_context_id IS NOT NULL)
            AND NOT EXISTS(
              SELECT 1 FROM offers private_offer JOIN orders private ON private.id=private_offer.order_id
              WHERE private_offer.courier_id=c.id AND private_offer.status='ACCEPTED'
                AND private.visitor_context_id IS NOT NULL
                AND private_offer.responded_at>=COALESCE((SELECT max(public_offer.responded_at)
                  FROM offers public_offer JOIN orders public_order ON public_order.id=public_offer.order_id
                  JOIN stores public_store ON public_store.id=public_order.store_id
                  WHERE public_offer.courier_id=c.id AND public_offer.status='ACCEPTED'
                    AND public_order.visitor_context_id IS NULL AND public_store.simulated),'-infinity'::timestamptz)))
            OR (:operations AND (EXISTS(
              SELECT 1 FROM users u JOIN demo_contexts d ON d.id=u.context_id
              WHERE u.courier_id=c.id AND u.context_id=CAST(:context AS uuid)
                AND u.expires_at>clock_timestamp() AND d.expires_at>d.created_at
                AND d.expires_at>clock_timestamp())
              OR EXISTS(SELECT 1 FROM orders own WHERE own.courier_id=c.id
                AND own.visitor_context_id=CAST(:context AS uuid)
                AND own.status IN ('ASSIGNED','AT_STORE','PICKED_UP'))
              OR EXISTS(SELECT 1 FROM offers f JOIN orders own ON own.id=f.order_id
                WHERE f.courier_id=c.id AND f.status='PENDING'
                  AND own.visitor_context_id=CAST(:context AS uuid)))))
        ORDER BY c.id LIMIT 2000
        """)
            .param("west", viewport.west())
            .param("south", viewport.south())
            .param("east", viewport.east())
            .param("north", viewport.north())
            .param("operations", operations)
            .param("context", principal == null ? null : principal.contextId())
            .query(
                (row, i) ->
                    List.<Object>of(
                        row.getString(1), row.getDouble(2), row.getDouble(3), row.getString(4), 0))
            .list();
    return new MapFrame(clock.instant(), points);
  }

  TrackingSnapshot tracking(String code) {
    if (code == null || !code.matches("[A-Za-z0-9_-]{20,64}")) {
      throw missing();
    }
    return jdbc.sql(
            """
        SELECT o.status,o.version,s.name,ST_Y(s.location::geometry) pickup_lat,
          ST_X(s.location::geometry) pickup_lon,ST_Y(o.dropoff::geometry) dropoff_lat,
          ST_X(o.dropoff::geometry) dropoff_lon,
          CASE WHEN c.location_updated_at>=clock_timestamp()-interval '30 seconds'
             AND o.status IN ('ASSIGNED','AT_STORE','PICKED_UP') THEN ST_Y(c.location::geometry) END courier_lat,
          CASE WHEN c.location_updated_at>=clock_timestamp()-interval '30 seconds'
             AND o.status IN ('ASSIGNED','AT_STORE','PICKED_UP') THEN ST_X(c.location::geometry) END courier_lon
        FROM orders o JOIN stores s ON s.id=o.store_id LEFT JOIN couriers c ON c.id=o.courier_id
        WHERE o.public_code=:code
        """)
        .param("code", code)
        .query(
            (row, i) ->
                new TrackingSnapshot(
                    OrderStatus.valueOf(row.getString("status")),
                    row.getLong("version"),
                    row.getString("name"),
                    new GeoPoint(row.getDouble("pickup_lat"), row.getDouble("pickup_lon")),
                    new GeoPoint(row.getDouble("dropoff_lat"), row.getDouble("dropoff_lon")),
                    row.getObject("courier_lat") == null
                        ? null
                        : new GeoPoint(row.getDouble("courier_lat"), row.getDouble("courier_lon")),
                    clock.instant()))
        .optional()
        .orElseThrow(RealtimeReadModel::missing);
  }

  String revision(DemoPrincipal principal) {
    return jdbc.sql(
                """
        SELECT md5(COALESCE(string_agg(o.id::text||':'||o.version||':'||COALESCE(e.last_id,0),',' ORDER BY o.id),''))
        FROM orders o JOIN stores s ON s.id=o.store_id
        LEFT JOIN LATERAL (SELECT max(id) last_id FROM order_events WHERE order_id=o.id) e ON true
        WHERE ((:role<>'COURIER' AND (o.visitor_context_id=:context OR (o.visitor_context_id IS NULL AND s.simulated)))
          OR (:role='COURIER' AND o.courier_id=CAST(:courier AS uuid)))
        """)
            .param("context", principal.contextId())
            .param("role", principal.role().name())
            .param("courier", principal.courierId())
            .query(String.class)
            .single()
        + (principal.courierId() == null
            ? ""
            : jdbc.sql(
                    "SELECT status||':'||version||':'||position_epoch||':'||COALESCE(location_updated_at::text,'') FROM couriers WHERE id=:id")
                .param("id", principal.courierId())
                .query(String.class)
                .optional()
                .orElse("missing"));
  }

  private static ApiException missing() {
    return new ApiException(
        HttpStatus.NOT_FOUND, "TRACKING_NOT_FOUND", "Não encontramos este rastreio.");
  }
}
