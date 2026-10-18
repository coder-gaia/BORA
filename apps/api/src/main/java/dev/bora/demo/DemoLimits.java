package dev.bora.demo;

import dev.bora.shared.ApiException;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;
import io.github.bucket4j.postgresql.PostgreSQLSelectForUpdateBasedProxyManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DemoLimits {

  private static final BucketConfiguration LOGIN =
      BucketConfiguration.builder()
          .addLimit(limit -> limit.capacity(10).refillGreedy(10, Duration.ofMinutes(1)))
          .addLimit(limit -> limit.capacity(30).refillGreedy(30, Duration.ofHours(1)))
          .build();
  private static final BucketConfiguration ORDERS =
      BucketConfiguration.builder()
          .addLimit(limit -> limit.capacity(10).refillGreedy(10, Duration.ofMinutes(1)))
          .build();
  private static final BucketConfiguration POSITIONS =
      BucketConfiguration.builder()
          .addLimit(limit -> limit.capacity(120).refillGreedy(60, Duration.ofMinutes(1)))
          .build();
  private final PostgreSQLSelectForUpdateBasedProxyManager<String> buckets;

  public DemoLimits(DataSource dataSource) {
    buckets =
        Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
            .primaryKeyMapper(PrimaryKeyMapper.STRING)
            .table("rate_limit_buckets")
            .expirationAfterWrite(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                    Duration.ofMinutes(10)))
            .build();
  }

  public void checkDemoLogin(String clientId) {
    check("demo:" + digest(Objects.requireNonNull(clientId)), LOGIN);
  }

  public void checkOrderCreation(UUID contextId) {
    check("orders:" + Objects.requireNonNull(contextId), ORDERS);
  }

  public void checkPositions(UUID courierId) {
    check("positions:" + Objects.requireNonNull(courierId), POSITIONS);
  }

  @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
  public void removeExpiredBuckets() {
    buckets.removeExpired(1_000);
  }

  private void check(String key, BucketConfiguration configuration) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Verifique o limite antes de abrir a transação do domínio.");
    }
    ConsumptionProbe probe =
        buckets.getProxy(key, () -> configuration).tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
      long retryAfter =
          Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "RATE_LIMIT_EXCEEDED",
          "Você fez muitas solicitações. Aguarde um pouco e tente novamente.",
          retryAfter);
    }
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("O resumo SHA-256 não está disponível.", failure);
    }
  }
}
