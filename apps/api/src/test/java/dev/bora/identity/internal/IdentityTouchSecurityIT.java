package dev.bora.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.routing.RoutingTools;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdentityTouchSecurityIT {
  private static final String MANIFEST = "routing/sp-fixture-manifest.json";
  private static final Path GRAPHS = Path.of("target", "routing-touch-it").toAbsolutePath();

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    RoutingTools.prepareGraph(MANIFEST, GRAPHS);
    registry.add("bora.routing.graph-root", GRAPHS::toString);
    registry.add("bora.routing.manifest", () -> MANIFEST);
  }

  @Autowired DemoIdentityService identity;
  @Autowired JdbcClient jdbc;
  @Autowired PlatformTransactionManager manager;

  @Test
  void rejectsEveryChangedPrincipalFieldBeforeRenewingAnything() {
    DemoPrincipal user = identity.login(DemoRole.STORE, null).principal();
    shortenExpiry(user.contextId());
    Instant contextExpiry = contextExpiry(user.contextId());
    Instant userExpiry = userExpiry(user.userId());
    List<DemoPrincipal> altered =
        List.of(
            new DemoPrincipal(
                UUID.randomUUID(), user.contextId(), user.role(), user.storeId(), null, true),
            new DemoPrincipal(
                user.userId(), UUID.randomUUID(), user.role(), user.storeId(), null, true),
            new DemoPrincipal(
                user.userId(), user.contextId(), DemoRole.OPERATIONS, null, null, true),
            new DemoPrincipal(
                user.userId(), user.contextId(), user.role(), UUID.randomUUID(), null, true),
            new DemoPrincipal(
                user.userId(), user.contextId(), DemoRole.COURIER, null, UUID.randomUUID(), true),
            new DemoPrincipal(
                user.userId(), user.contextId(), user.role(), user.storeId(), null, false));
    for (DemoPrincipal invalid : altered) {
      assertThat(identity.touch(invalid)).isEmpty();
      assertThat(contextExpiry(user.contextId())).isEqualTo(contextExpiry);
      assertThat(userExpiry(user.userId())).isEqualTo(userExpiry);
    }
    assertThat(identity.touch(user)).isPresent();
    assertThat(contextExpiry(user.contextId()))
        .isAfter(Instant.now().plus(Duration.ofMinutes(119)));
  }

  @Test
  void changedCourierLinkCannotRenewContext() {
    DemoPrincipal user = identity.login(DemoRole.COURIER, null).principal();
    shortenExpiry(user.contextId());
    Instant expires = contextExpiry(user.contextId());
    var invalid =
        new DemoPrincipal(
            user.userId(), user.contextId(), user.role(), null, UUID.randomUUID(), true);
    assertThat(identity.touch(invalid)).isEmpty();
    assertThat(contextExpiry(user.contextId())).isEqualTo(expires);
  }

  @Test
  void acceptsExactPrincipalsWithNullableRoleLinksAndRenewsAllRolesTogether() {
    DemoPrincipal store = identity.login(DemoRole.STORE, null).principal();
    DemoPrincipal courier = identity.login(DemoRole.COURIER, store.contextId()).principal();
    DemoPrincipal operations = identity.login(DemoRole.OPERATIONS, store.contextId()).principal();
    for (DemoPrincipal principal : List.of(store, courier, operations)) {
      shortenExpiry(store.contextId());
      assertThat(identity.touch(principal)).isPresent();
      assertThat(
              jdbc.sql(
                      "SELECT bool_and(expires_at>CURRENT_TIMESTAMP+INTERVAL '119 minutes') FROM users WHERE context_id=:id")
                  .param("id", store.contextId())
                  .query(Boolean.class)
                  .single())
          .isTrue();
    }
  }

  @Test
  void expiredUserCannotRenewItsStillActiveContext() {
    DemoPrincipal user = identity.login(DemoRole.OPERATIONS, null).principal();
    shortenExpiry(user.contextId());
    jdbc.sql("UPDATE users SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=:id")
        .param("id", user.userId())
        .update();
    Instant expires = contextExpiry(user.contextId());
    assertThat(identity.touch(user)).isEmpty();
    assertThat(contextExpiry(user.contextId())).isEqualTo(expires);
  }

  @Test
  void concurrentLogoutWinsBeforeWaitingTouchCanRenewContext() throws Exception {
    DemoPrincipal user = identity.login(DemoRole.OPERATIONS, null).principal();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting =
          new TransactionTemplate(manager)
              .execute(
                  status -> {
                    jdbc.sql("SELECT id FROM demo_contexts WHERE id=:id FOR UPDATE")
                        .param("id", user.contextId())
                        .query(UUID.class)
                        .single();
                    var touch = executor.submit(() -> identity.touch(user));
                    awaitContextLockWait();
                    identity.invalidate(user.contextId());
                    return touch;
                  });
      assertThat(waiting.get(5, TimeUnit.SECONDS)).isEmpty();
      assertThat(contextExpiry(user.contextId())).isBeforeOrEqualTo(Instant.now());
    }
  }

  @Test
  void loginWaitingBehindLogoutCreatesNewContextInsteadOfRevivingOldOne() throws Exception {
    DemoPrincipal user = identity.login(DemoRole.OPERATIONS, null).principal();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting =
          new TransactionTemplate(manager)
              .execute(
                  status -> {
                    jdbc.sql("SELECT id FROM demo_contexts WHERE id=:id FOR UPDATE")
                        .param("id", user.contextId())
                        .query(UUID.class)
                        .single();
                    var login =
                        executor.submit(
                            () -> identity.login(DemoRole.OPERATIONS, user.contextId()));
                    awaitContextLockWait();
                    identity.invalidate(user.contextId());
                    return login;
                  });
      assertThat(waiting.get(5, TimeUnit.SECONDS).principal().contextId())
          .isNotEqualTo(user.contextId());
      assertThat(contextExpiry(user.contextId())).isBeforeOrEqualTo(Instant.now());
    }
  }

  @Test
  void renewalPreservesLongerExpiryAlreadyStoredInDatabase() {
    DemoPrincipal user = identity.login(DemoRole.OPERATIONS, null).principal();
    jdbc.sql(
            "UPDATE demo_contexts SET expires_at=CURRENT_TIMESTAMP+INTERVAL '3 hours' WHERE id=:id")
        .param("id", user.contextId())
        .update();
    jdbc.sql("UPDATE users SET expires_at=CURRENT_TIMESTAMP+INTERVAL '3 hours' WHERE id=:id")
        .param("id", user.userId())
        .update();
    Instant contextExpiry = contextExpiry(user.contextId());
    Instant userExpiry = userExpiry(user.userId());
    assertThat(identity.touch(user))
        .get()
        .extracting(view -> view.expiresAt())
        .isEqualTo(userExpiry);
    assertThat(contextExpiry(user.contextId())).isEqualTo(contextExpiry);
    assertThat(userExpiry(user.userId())).isEqualTo(userExpiry);
  }

  private void awaitContextLockWait() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    boolean blocked = false;
    while (System.nanoTime() < deadline) {
      jdbc.sql("SELECT pg_stat_clear_snapshot()").query((row, index) -> true).single();
      blocked =
          jdbc.sql(
                  "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%WITH locked_context AS%')")
              .query(Boolean.class)
              .single();
      if (blocked) break;
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
    }
    assertThat(blocked).as("requisição deve aguardar o bloqueio do contexto").isTrue();
  }

  private void shortenExpiry(UUID context) {
    jdbc.sql(
            "UPDATE demo_contexts SET expires_at=CURRENT_TIMESTAMP+INTERVAL '1 minute' WHERE id=:id")
        .param("id", context)
        .update();
    jdbc.sql(
            "UPDATE users SET expires_at=CURRENT_TIMESTAMP+INTERVAL '1 minute' WHERE context_id=:id")
        .param("id", context)
        .update();
  }

  private Instant contextExpiry(UUID context) {
    return jdbc.sql("SELECT expires_at FROM demo_contexts WHERE id=:id")
        .param("id", context)
        .query(OffsetDateTime.class)
        .single()
        .toInstant();
  }

  private Instant userExpiry(UUID user) {
    return jdbc.sql("SELECT expires_at FROM users WHERE id=:id")
        .param("id", user)
        .query(OffsetDateTime.class)
        .single()
        .toInstant();
  }
}
