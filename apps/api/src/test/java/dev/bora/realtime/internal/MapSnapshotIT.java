package dev.bora.realtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.realtime.MapFrame;
import dev.bora.realtime.MapViewport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MapSnapshotIT {
  @Container
  static final PostgreSQLContainer DATABASE =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  private DataSource dataSource;
  private JdbcClient jdbc;

  @BeforeAll
  void migrate() {
    dataSource =
        new DriverManagerDataSource(
            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = JdbcClient.create(dataSource);
  }

  @BeforeEach
  void clearPositions() {
    jdbc.sql("UPDATE couriers SET status='OFFLINE'").update();
  }

  @ParameterizedTest
  @EnumSource(MapViewport.Scope.class)
  @Timeout(30)
  void anOlderMapReadKeepsItsCaptureTimeWhenItCompletesAfterANewerRead(MapViewport.Scope scope)
      throws Exception {
    UUID courier = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO couriers(id,name,status,simulated,location,location_updated_at)
        VALUES(:id,'Mapa com leitura atrasada','AVAILABLE',true,
          ST_GeogFromText('SRID=4326;POINT(-46.6559 -23.5614)'),clock_timestamp())
        """)
        .param("id", courier)
        .update();
    DemoPrincipal principal = scope == MapViewport.Scope.OPERATIONS ? operations() : null;
    var delayed = new DelayedMapQueries(dataSource);
    // Different application clocks cannot reorder snapshots from their shared database.
    var first = model(JdbcClient.create(delayed), "2099-01-01T00:00:00Z");
    var second = model(jdbc, "2000-01-01T00:00:00Z");
    MapViewport viewport = viewport(scope);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = executor.submit(() -> first.map(viewport, principal));
      try {
        assertThat(delayed.captured.await(10, TimeUnit.SECONDS)).isTrue();
        Instant afterFirstCapture = databaseTime();
        jdbc.sql(
                """
            UPDATE couriers SET location=ST_GeogFromText('SRID=4326;POINT(-46.6549 -23.5614)'),
              location_updated_at=clock_timestamp() WHERE id=:id
            """)
            .param("id", courier)
            .update();
        MapFrame newer = second.map(viewport, principal);
        delayed.release.countDown();
        MapFrame older = pending.get(10, TimeUnit.SECONDS);
        assertThat(older.points())
            .containsExactly(List.of(courier.toString(), -46.6559, -23.5614, "AVAILABLE", 0));
        assertThat(newer.points())
            .containsExactly(List.of(courier.toString(), -46.6549, -23.5614, "AVAILABLE", 0));
        assertThat(older.serverTime()).isBeforeOrEqualTo(afterFirstCapture);
        assertThat(newer.serverTime()).isAfterOrEqualTo(afterFirstCapture);
        assertThat(older.serverTime()).isBefore(newer.serverTime());
      } finally {
        delayed.release.countDown();
      }
    }
  }

  @Test
  void emptyPublicAndOperationsSnapshotsUseTheDatabaseClockWithoutCreatingAPoint() {
    for (MapViewport.Scope scope : MapViewport.Scope.values()) {
      DemoPrincipal principal = scope == MapViewport.Scope.OPERATIONS ? operations() : null;
      Instant before = databaseTime();
      MapFrame empty = model(jdbc, "2099-01-01T00:00:00Z").map(viewport(scope), principal);
      Instant after = databaseTime();
      assertThat(empty.points()).isEmpty();
      assertThat(empty.serverTime()).isBetween(before, after);
    }
  }

  private RealtimeReadModel model(JdbcClient client, String localTime) {
    return new RealtimeReadModel(
        client,
        Clock.fixed(Instant.parse(localTime), ZoneOffset.UTC),
        mock(TrackingRoutes.class),
        new DataSourceTransactionManager(dataSource));
  }

  private static MapViewport viewport(MapViewport.Scope scope) {
    return new MapViewport(-46.66, -23.57, -46.65, -23.55, scope);
  }

  private Instant databaseTime() {
    return jdbc.sql("SELECT clock_timestamp()")
        .query((row, index) -> row.getTimestamp(1).toInstant())
        .single();
  }

  private DemoPrincipal operations() {
    UUID context = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO demo_contexts(id,expires_at) VALUES(:id,clock_timestamp()+interval '1 hour')")
        .param("id", context)
        .update();
    jdbc.sql(
            """
        INSERT INTO users(id,context_id,display_name,role,visitor,expires_at)
        VALUES(:id,:context,'Operação do mapa','OPERATIONS',true,clock_timestamp()+interval '1 hour')
        """)
        .param("id", user)
        .param("context", context)
        .update();
    return new DemoPrincipal(user, context, DemoRole.OPERATIONS, null, null, true);
  }

  private static final class DelayedMapQueries extends NamedParameterJdbcTemplate {
    private final CountDownLatch captured = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    DelayedMapQueries(DataSource source) {
      super(source);
    }

    @Override
    public <T> List<T> query(String sql, SqlParameterSource parameters, RowMapper<T> mapper) {
      List<T> result = super.query(sql, parameters, mapper);
      if (sql.contains("FROM couriers c")) {
        captured.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS))
            throw new IllegalStateException("A leitura do mapa não foi liberada pelo teste.");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("A leitura do mapa foi interrompida.", interrupted);
        }
      }
      return result;
    }
  }
}
