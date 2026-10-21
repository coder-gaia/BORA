package dev.bora.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.bora.BoraApplication;
import dev.bora.routing.RoutingTools;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** O prazo é persistido; avançar o relógio não exige esperas de parede nem timers falsos. */
@Testcontainers
class DispatchRestartIT {
  private static final String MANIFEST = "routing/sp-fixture-manifest.json";
  private static final Path GRAPHS = Path.of("target", "routing-restart-it").toAbsolutePath();
  private static final TestClock CLOCK = new TestClock();

  @Container
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @Test
  void restartingOneOfTwoInstancesPreservesTheOfferAndItsOriginalDeadline() throws Exception {
    RoutingTools.prepareGraph(MANIFEST, GRAPHS);
    CLOCK.at.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
    ConfigurableApplicationContext first = start("first");
    ConfigurableApplicationContext restarted = null;
    try (ConfigurableApplicationContext survivor = start("survivor")) {
      var firstPool = first.getBean(DataSource.class).unwrap(HikariDataSource.class);
      var survivingPool = survivor.getBean(DataSource.class).unwrap(HikariDataSource.class);
      assertThat(firstPool).isNotSameAs(survivingPool);
      var jdbc = survivor.getBean(JdbcClient.class);
      UUID order = UUID.randomUUID();
      UUID courier = UUID.randomUUID();
      jdbc.sql(
              """
          INSERT INTO couriers(id,name,status,location,location_updated_at,simulated)
          VALUES(:id,'Entregador reinício','AVAILABLE',
            ST_SetSRID(ST_MakePoint(-46.6559,-23.5614),4326)::geography,:now,true)
          """)
          .param("id", courier)
          .param("now", time(CLOCK.instant()))
          .update();
      jdbc.sql(
              """
          INSERT INTO orders(id,public_code,store_id,dropoff,status,sla_deadline,next_attempt_at,created_at)
          VALUES(:id,:code,'20000000-0000-4000-8000-000000000001',
            ST_SetSRID(ST_MakePoint(-46.6558,-23.56135),4326)::geography,
            'SEARCHING',:sla,:now,:now)
          """)
          .param("id", order)
          .param("code", UUID.randomUUID().toString())
          .param("sla", time(CLOCK.instant().plusSeconds(1800)))
          .param("now", time(CLOCK.instant()))
          .update();
      assertThat(first.getBean(DispatchCoordinator.class).dispatchAvailableOrders()).isEqualTo(1);
      UUID offer =
          jdbc.sql("SELECT id FROM offers WHERE order_id=:id AND status='PENDING'")
              .param("id", order)
              .query(UUID.class)
              .single();
      Instant deadline = deadline(jdbc, offer);
      assertThat(deadline).isEqualTo(CLOCK.instant().plusSeconds(30));
      Object previousCoordinator = first.getBean(DispatchCoordinator.class);
      first.close();
      first = null;
      assertThat(firstPool.isClosed()).isTrue();
      assertThat(survivingPool.isClosed()).isFalse();
      CLOCK.at.set(deadline.minusNanos(1_000));
      assertThat(survivor.getBean(PendingOfferCleanup.class).expireDueOffers()).isZero();
      restarted = start("restarted");
      assertThat(restarted.getBean(DispatchCoordinator.class)).isNotSameAs(previousCoordinator);
      assertThat(status(jdbc, offer)).isEqualTo("PENDING");
      assertThat(deadline(jdbc, offer)).isEqualTo(deadline);
      assertThat(
              jdbc.sql("SELECT status FROM couriers WHERE id=:id")
                  .param("id", courier)
                  .query(String.class)
                  .single())
          .isEqualTo("OFFERED");
      CLOCK.at.set(deadline);
      assertThat(restarted.getBean(PendingOfferCleanup.class).expireDueOffers()).isEqualTo(1);
      assertThat(survivor.getBean(PendingOfferCleanup.class).expireDueOffers()).isZero();
      assertThat(status(jdbc, offer)).isEqualTo("EXPIRED");
      assertThat(deadline(jdbc, offer)).isEqualTo(deadline);
      assertThat(
              jdbc.sql("SELECT responded_at FROM offers WHERE id=:id")
                  .param("id", offer)
                  .query(OffsetDateTime.class)
                  .single()
                  .toInstant())
          .isEqualTo(deadline);
      assertThat(
              jdbc.sql("SELECT status FROM couriers WHERE id=:id")
                  .param("id", courier)
                  .query(String.class)
                  .single())
          .isEqualTo("AVAILABLE");
      assertThat(
              jdbc.sql("SELECT next_attempt_at FROM orders WHERE id=:id")
                  .param("id", order)
                  .query(OffsetDateTime.class)
                  .single()
                  .toInstant())
          .isEqualTo(deadline);
      assertThat(
              jdbc.sql(
                      "SELECT count(*) FROM order_events WHERE order_id=:id AND type='OFFER_EXPIRED'")
                  .param("id", order)
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
    } finally {
      if (restarted != null) restarted.close();
      if (first != null) first.close();
    }
  }

  private static ConfigurableApplicationContext start(String name) {
    return new SpringApplicationBuilder(BoraApplication.class, Dependencies.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.application.name=bora-restart-it-" + name,
            "--spring.datasource.url=" + database.getJdbcUrl(),
            "--spring.datasource.username=" + database.getUsername(),
            "--spring.datasource.password=" + database.getPassword(),
            "--bora.routing.graph-root=" + GRAPHS,
            "--bora.routing.manifest=" + MANIFEST,
            "--bora.dispatch.require-activation=false",
            "--bora.dispatch.worker-enabled=false",
            "--bora.dispatch.expiry-enabled=false",
            "--bora.fulfillment.reconciliation-enabled=false",
            "--bora.couriers.flush-initial-delay-ms=3600000");
  }

  private static Instant deadline(JdbcClient jdbc, UUID id) {
    return jdbc.sql("SELECT expires_at FROM offers WHERE id=:id")
        .param("id", id)
        .query(OffsetDateTime.class)
        .single()
        .toInstant();
  }

  private static String status(JdbcClient jdbc, UUID id) {
    return jdbc.sql("SELECT status FROM offers WHERE id=:id")
        .param("id", id)
        .query(String.class)
        .single();
  }

  private static OffsetDateTime time(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Dependencies {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  private static final class TestClock extends Clock {
    final AtomicReference<Instant> at = new AtomicReference<>();

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return at.get();
    }
  }
}
