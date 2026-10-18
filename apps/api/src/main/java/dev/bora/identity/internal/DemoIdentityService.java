package dev.bora.identity.internal;

import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.identity.DemoUserView;
import dev.bora.shared.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DemoIdentityService {

  private static final Duration IDLE_TIMEOUT = Duration.ofHours(2);
  private final JdbcClient jdbc;
  private final Clock clock;

  DemoIdentityService(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  Login login(DemoRole role, UUID previousContext) {
    Instant now = clock.instant();
    UUID context = previousContext;
    if (context == null || !lockActiveContext(context, now)) {
      context = UUID.randomUUID();
      jdbc.sql(
              "INSERT INTO demo_contexts (id, created_at, expires_at) VALUES (:id, :now, :expires)")
          .param("id", context)
          .param("now", timestamp(now))
          .param("expires", timestamp(now.plus(IDLE_TIMEOUT)))
          .update();
    }
    extendContext(context, now);
    Optional<User> existing =
        jdbc.sql("SELECT * FROM users WHERE context_id = :context AND role = :role")
            .param("context", context)
            .param("role", role.name())
            .query(DemoIdentityService::readUser)
            .optional();
    User user = existing.orElse(null);
    if (user == null) {
      user = createUser(context, role, now);
    }
    return new Login(user.principal(), user.view());
  }

  Optional<DemoUserView> touch(DemoPrincipal principal) {
    Instant now = clock.instant();
    Optional<User> result =
        jdbc.sql(
                """
                WITH locked_context AS MATERIALIZED (
                  SELECT id,expires_at FROM demo_contexts
                  WHERE id=:context AND expires_at>created_at AND expires_at>:now FOR UPDATE
                ), valid_user AS MATERIALIZED (
                  SELECT u.* FROM locked_context c JOIN users u ON u.context_id=c.id
                  WHERE c.expires_at>clock_timestamp()
                    AND u.id=:id AND u.expires_at>:now AND u.expires_at>clock_timestamp()
                    AND u.role=:role
                    AND u.store_id IS NOT DISTINCT FROM CAST(:store AS uuid)
                    AND u.courier_id IS NOT DISTINCT FROM CAST(:courier AS uuid)
                    AND u.visitor=:visitor
                ), renewed_context AS (
                  UPDATE demo_contexts c SET expires_at=GREATEST(c.expires_at,:expires)
                  FROM valid_user u WHERE c.id=u.context_id RETURNING c.id
                ), renewed_users AS (
                  UPDATE users u SET expires_at=GREATEST(u.expires_at,:expires)
                  FROM renewed_context c WHERE u.context_id=c.id RETURNING u.*
                )
                SELECT * FROM renewed_users WHERE id=:id
                """)
            .param("id", principal.userId())
            .param("context", principal.contextId())
            .param("now", timestamp(now))
            .param("role", principal.role().name())
            .param("store", principal.storeId())
            .param("courier", principal.courierId())
            .param("visitor", principal.visitor())
            .param("expires", timestamp(now.plus(IDLE_TIMEOUT)))
            .query(DemoIdentityService::readUser)
            .optional();
    if (result.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(result.get().view());
  }

  void invalidate(UUID context) {
    if (context == null) {
      return;
    }
    jdbc.sql("UPDATE demo_contexts SET expires_at = created_at WHERE id = :id")
        .param("id", context)
        .update();
    jdbc.sql("UPDATE users SET expires_at = :now WHERE context_id = :id")
        .param("id", context)
        .param("now", timestamp(clock.instant()))
        .update();
  }

  private boolean lockActiveContext(UUID context, Instant now) {
    return jdbc.sql(
            """
        WITH locked_context AS MATERIALIZED (
          SELECT id,expires_at FROM demo_contexts
                  WHERE id=:id AND expires_at>created_at AND expires_at>:now FOR UPDATE
        ) SELECT id FROM locked_context WHERE expires_at>clock_timestamp()
        """)
        .param("id", context)
        .param("now", timestamp(now))
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  private void extendContext(UUID context, Instant now) {
    jdbc.sql("UPDATE demo_contexts SET expires_at = GREATEST(expires_at, :expires) WHERE id = :id")
        .param("id", context)
        .param("expires", timestamp(now.plus(IDLE_TIMEOUT)))
        .update();
    jdbc.sql("UPDATE users SET expires_at = GREATEST(expires_at, :expires) WHERE context_id = :id")
        .param("id", context)
        .param("expires", timestamp(now.plus(IDLE_TIMEOUT)))
        .update();
  }

  private User createUser(UUID context, DemoRole role, Instant now) {
    UUID id = UUID.randomUUID();
    UUID store = null;
    UUID courier = null;
    String name =
        switch (role) {
          case STORE -> "Minha loja de demonstração";
          case COURIER -> "Entregador visitante";
          case OPERATIONS -> "Operação visitante";
        };
    if (role == DemoRole.STORE) {
      store = UUID.randomUUID();
      int created =
          jdbc.sql(
                  """
                  INSERT INTO stores (id, name, service_area_id, location, simulated)
                  SELECT :id, :name, service_area_id, location, false
                  FROM stores WHERE simulated ORDER BY id LIMIT 1
                  """)
              .param("id", store)
              .param("name", name)
              .update();
      if (created != 1) {
        throw new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "DEMO_UNAVAILABLE",
            "A demonstração ainda não está pronta. Tente novamente em instantes.");
      }
    } else if (role == DemoRole.COURIER) {
      courier = UUID.randomUUID();
      jdbc.sql(
              "INSERT INTO couriers (id, name, status, simulated) VALUES (:id, :name, 'OFFLINE', false)")
          .param("id", courier)
          .param("name", name)
          .update();
    }
    Instant expires = now.plus(IDLE_TIMEOUT);
    jdbc.sql(
            """
            INSERT INTO users (id, context_id, display_name, role, store_id, courier_id, visitor, expires_at)
            VALUES (:id, :context, :name, :role, :store, :courier, true, :expires)
            """)
        .param("id", id)
        .param("context", context)
        .param("name", name)
        .param("role", role.name())
        .param("store", store)
        .param("courier", courier)
        .param("expires", timestamp(expires))
        .update();
    return new User(id, context, name, role, store, courier, true, expires);
  }

  private static User readUser(java.sql.ResultSet row, int index) throws java.sql.SQLException {
    return new User(
        row.getObject("id", UUID.class),
        row.getObject("context_id", UUID.class),
        row.getString("display_name"),
        DemoRole.valueOf(row.getString("role")),
        row.getObject("store_id", UUID.class),
        row.getObject("courier_id", UUID.class),
        row.getBoolean("visitor"),
        row.getObject("expires_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  record Login(DemoPrincipal principal, DemoUserView user) {}

  private record User(
      UUID id,
      UUID context,
      String name,
      DemoRole role,
      UUID store,
      UUID courier,
      boolean visitor,
      Instant expires) {
    DemoPrincipal principal() {
      return new DemoPrincipal(id, context, role, store, courier, visitor);
    }

    DemoUserView view() {
      return new DemoUserView(id, name, role, store, courier, expires);
    }
  }
}
