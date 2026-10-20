package dev.bora.operations.internal;

import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.operations.OperationsMetrics;
import dev.bora.shared.ApiException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class OperationsMetricsService {
  private static final String SNAPSHOT =
      """
      WITH moment AS MATERIALIZED (SELECT CAST(:now AS timestamptz) server_time),
      actor AS MATERIALIZED (
        SELECT EXISTS (
          SELECT 1 FROM users u JOIN demo_contexts dc ON dc.id=u.context_id
          WHERE u.id=:user AND u.context_id=:context AND u.role='OPERATIONS'
            AND u.store_id IS NULL AND u.courier_id IS NULL AND u.visitor=:visitor
            AND u.expires_at>clock_timestamp() AND dc.expires_at>dc.created_at
            AND dc.expires_at>clock_timestamp()
        ) active
      ),
      visible AS MATERIALIZED (
        SELECT o.status,o.sla_deadline,o.delivered_at,
          GREATEST(o.created_at,COALESCE(attempt.started_at,o.created_at)) searching_since
        FROM orders o JOIN stores s ON s.id=o.store_id CROSS JOIN actor
        LEFT JOIN LATERAL (
          SELECT e.occurred_at started_at FROM order_events e
          WHERE e.order_id=o.id AND e.type='ORDER_REASSIGNED' AND e.order_version<=o.version
          ORDER BY e.order_version DESC,e.id DESC LIMIT 1
        ) attempt ON o.status='SEARCHING'
        WHERE actor.active AND (o.visitor_context_id=:context
          OR (o.visitor_context_id IS NULL AND s.simulated))
      )
      SELECT moment.server_time,actor.active,
        count(*) FILTER(WHERE v.status IN ('SEARCHING','ASSIGNED','AT_STORE','PICKED_UP')) active_orders,
        count(*) FILTER(WHERE v.status='SEARCHING') searching_orders,
        count(*) FILTER(WHERE v.status='SEARCHING'
          AND v.searching_since<=moment.server_time-interval '3 minutes') searching_over_three_minutes,
        count(*) FILTER(WHERE v.status IN ('SEARCHING','ASSIGNED','AT_STORE','PICKED_UP')
          AND v.sla_deadline<=moment.server_time) overdue_orders,
        count(*) FILTER(WHERE v.status IN ('SEARCHING','ASSIGNED','AT_STORE','PICKED_UP')
          AND v.sla_deadline>moment.server_time AND v.sla_deadline<=moment.server_time+interval '5 minutes') due_within_five_minutes,
        count(*) FILTER(WHERE v.status='DELIVERED'
          AND v.delivered_at>=moment.server_time-interval '60 minutes' AND v.delivered_at<=moment.server_time) delivered_last_hour
      FROM moment CROSS JOIN actor LEFT JOIN visible v ON true
      GROUP BY moment.server_time,actor.active
      """;

  private final JdbcClient jdbc;
  private final Clock clock;

  OperationsMetricsService(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  OperationsMetrics read(DemoPrincipal user) {
    if (user.role() != DemoRole.OPERATIONS) {
      throw new ApiException(
          HttpStatus.FORBIDDEN,
          "OPERATIONS_ROLE_REQUIRED",
          "Entre na operação para consultar os indicadores.");
    }
    var snapshot =
        jdbc.sql(SNAPSHOT)
            .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .param("user", user.userId())
            .param("context", user.contextId())
            .param("visitor", user.visitor())
            .query(
                (row, index) ->
                    new Snapshot(
                        row.getBoolean("active"),
                        new OperationsMetrics(
                            row.getLong("active_orders"),
                            row.getLong("searching_orders"),
                            row.getLong("searching_over_three_minutes"),
                            row.getLong("overdue_orders"),
                            row.getLong("due_within_five_minutes"),
                            row.getLong("delivered_last_hour"),
                            row.getTimestamp("server_time").toInstant())))
            .single();
    if (!snapshot.active()) {
      throw new ApiException(
          HttpStatus.UNAUTHORIZED,
          "DEMO_EXPIRED",
          "Sua demonstração expirou. Entre novamente para continuar.");
    }
    return snapshot.metrics();
  }

  private record Snapshot(boolean active, OperationsMetrics metrics) {}
}
