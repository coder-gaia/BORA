package dev.bora.realtime.internal;

import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.orders.OrderStatus;
import dev.bora.realtime.MapFrame;
import dev.bora.realtime.MapViewport;
import dev.bora.realtime.TrackingEvent;
import dev.bora.realtime.TrackingRoute;
import dev.bora.realtime.TrackingSnapshot;
import dev.bora.routing.GeoPoint;
import dev.bora.shared.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class RealtimeReadModel {
  private final JdbcClient jdbc;
  private final Clock clock;
  private final TrackingRoutes routes;
  private final TransactionTemplate readTransaction;

  RealtimeReadModel(
      JdbcClient jdbc, Clock clock, TrackingRoutes routes, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.routes = routes;
    this.readTransaction = new TransactionTemplate(manager);
    this.readTransaction.setReadOnly(true);
    this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
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
    var captured =
        jdbc.sql(
                """
        WITH visible AS (
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
        )
        SELECT statement_timestamp() observed_at,visible.*
        FROM (SELECT 1) anchor LEFT JOIN visible ON true
        ORDER BY visible.id
        """)
            .param("west", viewport.west())
            .param("south", viewport.south())
            .param("east", viewport.east())
            .param("north", viewport.north())
            .param("operations", operations)
            .param("context", principal == null ? null : principal.contextId())
            .query(
                (row, i) ->
                    new ObservedMapPoint(
                        row.getTimestamp("observed_at").toInstant(),
                        row.getString("id") == null
                            ? null
                            : List.<Object>of(
                                row.getString("id"),
                                row.getDouble("longitude"),
                                row.getDouble("latitude"),
                                row.getString("status"),
                                0)))
            .list();
    return new MapFrame(
        captured.getFirst().observedAt(),
        captured.stream()
            .filter(point -> point.point() != null)
            .map(ObservedMapPoint::point)
            .toList());
  }

  private record ObservedMapPoint(Instant observedAt, List<Object> point) {}

  TrackingSnapshot tracking(String code) {
    if (code == null || !code.matches("[A-Za-z0-9_-]{20,64}")) {
      throw missing();
    }
    TrackingState state =
        readTransaction.execute(
            ignored -> {
              TrackingState order = trackingState(code);
              List<TrackingEvent> timeline =
                  jdbc.sql(
                          """
                  SELECT type,payload->>'from' from_status,payload->>'to' to_status,
                    occurred_at,order_version
                  FROM order_events WHERE order_id=:id AND order_version<=:version
                    AND type IN ('ORDER_CREATED','ORDER_ASSIGNED','ORDER_AT_STORE',
                      'ORDER_PICKED_UP','ORDER_DELIVERED','ORDER_CANCELED','ORDER_REASSIGNED')
                    AND payload->>'to' IN ('SEARCHING','ASSIGNED','AT_STORE','PICKED_UP','DELIVERED','CANCELED')
                  ORDER BY occurred_at,id
                  """)
                      .param("id", order.id())
                      .param("version", order.version())
                      .query(
                          (row, index) ->
                              new TrackingEvent(
                                  row.getString("type"),
                                  row.getString("from_status") == null
                                      ? null
                                      : OrderStatus.valueOf(row.getString("from_status")),
                                  OrderStatus.valueOf(row.getString("to_status")),
                                  row.getTimestamp("occurred_at").toInstant(),
                                  row.getLong("order_version")))
                      .list();
              return order.withTimeline(timeline);
            });
    // O cálculo de rota fica fora da transação e não guarda autorização nem snapshots privados.
    TrackingRoute route =
        routes.route(
            state.id(),
            state.version(),
            state.status(),
            state.courierId(),
            state.pickup(),
            state.dropoff(),
            state.courierPosition());
    Long eta =
        route != null
                && (state.status() == OrderStatus.ASSIGNED
                    || state.status() == OrderStatus.PICKED_UP)
            ? (long) Math.ceil(route.simulatedDurationMillis() / 1000.0)
            : null;
    return new TrackingSnapshot(
        state.status(),
        state.version(),
        state.storeName(),
        state.pickup(),
        state.dropoff(),
        state.courierPosition(),
        state.courierPositionUpdatedAt(),
        state.createdAt(),
        state.timeline(),
        route,
        eta,
        state.observedAt());
  }

  private TrackingState trackingState(String code) {
    return jdbc.sql(
            """
        SELECT o.id,o.status,o.version,o.courier_id,o.created_at,statement_timestamp() observed_at,
          s.name,ST_Y(s.location::geometry) pickup_lat,
          ST_X(s.location::geometry) pickup_lon,ST_Y(o.dropoff::geometry) dropoff_lat,
          ST_X(o.dropoff::geometry) dropoff_lon,
          CASE WHEN c.location_updated_at>=clock_timestamp()-interval '30 seconds'
             AND c.location_updated_at>=o.assigned_at
             AND o.status IN ('ASSIGNED','AT_STORE','PICKED_UP') THEN ST_Y(c.location::geometry) END courier_lat,
          CASE WHEN c.location_updated_at>=clock_timestamp()-interval '30 seconds'
             AND c.location_updated_at>=o.assigned_at
             AND o.status IN ('ASSIGNED','AT_STORE','PICKED_UP') THEN ST_X(c.location::geometry) END courier_lon,
          CASE WHEN c.location_updated_at>=clock_timestamp()-interval '30 seconds'
             AND c.location_updated_at>=o.assigned_at AND c.location IS NOT NULL
             AND o.status IN ('ASSIGNED','AT_STORE','PICKED_UP') THEN c.location_updated_at END position_updated_at
        FROM orders o JOIN stores s ON s.id=o.store_id LEFT JOIN couriers c ON c.id=o.courier_id
        WHERE o.public_code=:code
          AND (o.visitor_context_id IS NULL OR EXISTS(SELECT 1 FROM demo_contexts d
            WHERE d.id=o.visitor_context_id AND d.expires_at>d.created_at AND d.expires_at>clock_timestamp()))
        """)
        .param("code", code)
        .query(
            (row, i) ->
                new TrackingState(
                    row.getObject("id", UUID.class),
                    OrderStatus.valueOf(row.getString("status")),
                    row.getLong("version"),
                    row.getObject("courier_id", UUID.class),
                    row.getString("name"),
                    new GeoPoint(row.getDouble("pickup_lat"), row.getDouble("pickup_lon")),
                    new GeoPoint(row.getDouble("dropoff_lat"), row.getDouble("dropoff_lon")),
                    row.getObject("courier_lat") == null
                        ? null
                        : new GeoPoint(row.getDouble("courier_lat"), row.getDouble("courier_lon")),
                    row.getTimestamp("position_updated_at") == null
                        ? null
                        : row.getTimestamp("position_updated_at").toInstant(),
                    row.getTimestamp("created_at").toInstant(),
                    row.getTimestamp("observed_at").toInstant(),
                    List.of()))
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

  private record TrackingState(
      UUID id,
      OrderStatus status,
      long version,
      UUID courierId,
      String storeName,
      GeoPoint pickup,
      GeoPoint dropoff,
      GeoPoint courierPosition,
      Instant courierPositionUpdatedAt,
      Instant createdAt,
      Instant observedAt,
      List<TrackingEvent> timeline) {
    TrackingState withTimeline(List<TrackingEvent> events) {
      return new TrackingState(
          id,
          status,
          version,
          courierId,
          storeName,
          pickup,
          dropoff,
          courierPosition,
          courierPositionUpdatedAt,
          createdAt,
          observedAt,
          events);
    }
  }
}
