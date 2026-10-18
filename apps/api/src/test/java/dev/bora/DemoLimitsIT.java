package dev.bora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bora.demo.DemoLimits;
import dev.bora.shared.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DemoLimitsIT {

  @Container
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  private static DriverManagerDataSource dataSource;

  @BeforeAll
  static void migrate() {
    dataSource =
        new DriverManagerDataSource(
            database.getJdbcUrl(), database.getUsername(), database.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
  }

  @Test
  void serializesGlobalOrderBudgetAcrossIndependentManagersAndConcurrentRequests()
      throws Exception {
    DemoLimits first = new DemoLimits(dataSource);
    DemoLimits second = new DemoLimits(dataSource);
    UUID context = UUID.randomUUID();
    List<Callable<Boolean>> requests = new ArrayList<>();
    for (int index = 0; index < 40; index++) {
      DemoLimits instance = index % 2 == 0 ? first : second;
      requests.add(
          () -> {
            try {
              instance.checkOrderCreation(context);
              return true;
            } catch (ApiException denied) {
              assertThat(denied.status().value()).isEqualTo(429);
              assertThat(denied.retryAfterSeconds()).isPositive();
              return false;
            }
          });
    }
    try (var executor = Executors.newFixedThreadPool(8)) {
      long accepted = 0;
      for (var result : executor.invokeAll(requests)) {
        if (result.get()) {
          accepted++;
        }
      }
      assertThat(accepted).isEqualTo(10);
    }
    new DemoLimits(dataSource).checkOrderCreation(UUID.randomUUID());
    assertThatThrownBy(() -> new DemoLimits(dataSource).checkOrderCreation(context))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void keepsLoginBudgetAcrossManagerRestartWithoutStoringClientAddress() {
    String client = "client-" + UUID.randomUUID();
    for (int index = 0; index < 10; index++) {
      new DemoLimits(dataSource).checkDemoLogin(client);
    }
    assertThatThrownBy(() -> new DemoLimits(dataSource).checkDemoLogin(client))
        .isInstanceOf(ApiException.class);
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
    assertThat(
            jdbc.sql("SELECT count(*) FROM rate_limit_buckets WHERE id LIKE :address")
                .param("address", "%" + client + "%")
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void refusesToBorrowAnotherConnectionInsideDomainTransaction() {
    DemoLimits limits = new DemoLimits(dataSource);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> limits.checkOrderCreation(UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("antes de abrir a transação");
  }

  @Test
  void cleansExpiredBucketsInSharedStorage() {
    DemoLimits limits = new DemoLimits(dataSource);
    UUID courier = UUID.randomUUID();
    limits.checkPositions(courier);
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
    jdbc.sql("UPDATE rate_limit_buckets SET expires_at=0 WHERE id=:id")
        .param("id", "positions:" + courier)
        .update();
    limits.removeExpiredBuckets();
    assertThat(
            jdbc.sql("SELECT count(*) FROM rate_limit_buckets WHERE id=:id")
                .param("id", "positions:" + courier)
                .query(Integer.class)
                .single())
        .isZero();
  }
}
