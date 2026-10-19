package dev.bora.simulator;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.SplittableRandom;

final class DemandCurve {
  private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
  private static final double[] PER_MINUTE = {
    6, 6, 6, 6, 6, 6, 7, 8, 9, 10, 12, 16,
    18, 18, 14, 11, 10, 12, 16, 22, 22, 18, 12, 8
  };

  double ordersPerMinute(Instant instant) {
    var local = instant.atZone(SAO_PAULO);
    double fraction = (local.getMinute() * 60 + local.getSecond()) / 3_600.0;
    int hour = local.getHour();
    return PER_MINUTE[hour] * (1 - fraction) + PER_MINUTE[(hour + 1) % 24] * fraction;
  }

  Duration nextOrder(Instant instant, int storeCount, SplittableRandom random) {
    return nextOrder(instant, storeCount, 1, random);
  }

  Duration nextOrder(Instant instant, int storeCount, double weight, SplittableRandom random) {
    if (storeCount < 1) {
      throw new IllegalArgumentException("Informe ao menos uma loja.");
    }
    if (!Double.isFinite(weight) || weight <= 0) {
      throw new IllegalArgumentException("Peso de demanda inválido.");
    }
    double meanMillis = 60_000.0 * storeCount / (ordersPerMinute(instant) * weight);
    double sample = -Math.log1p(-random.nextDouble()) * meanMillis;
    return Duration.ofMillis(Math.max(1_000, (long) sample));
  }

  double storeWeight(int index, int storeCount, Instant instant) {
    if (storeCount < 2) {
      return 1;
    }
    int hour = instant.atZone(SAO_PAULO).getHour();
    boolean firstRegion = index < storeCount / 2;
    if (hour >= 10 && hour < 15) {
      return firstRegion ? 1.6 : 0.4;
    }
    if (hour >= 17 && hour < 23) {
      return firstRegion ? 0.4 : 1.6;
    }
    return 1;
  }

  static SplittableRandom actorRandom(long seed, String role, int index) {
    // Cada ator tem uma sequência própria; escalonamento das threads não altera o sorteio alheio.
    return new SplittableRandom(
        seed ^ ((long) role.hashCode() << 32) ^ (index * 0x9E3779B97F4A7C15L));
  }

  static OfferDecision offerDecision(SplittableRandom random) {
    double draw = random.nextDouble();
    OfferAction action =
        draw < 0.85 ? OfferAction.ACCEPT : draw < 0.95 ? OfferAction.REJECT : OfferAction.TIMEOUT;
    return new OfferDecision(action, Duration.ofMillis(random.nextLong(3_000, 12_001)));
  }

  static Duration storeWait(SplittableRandom random, double speed) {
    if (!Double.isFinite(speed) || speed <= 0) {
      throw new IllegalArgumentException("Velocidade inválida.");
    }
    return Duration.ofMillis((long) (random.nextLong(60_000, 180_001) / speed));
  }

  enum OfferAction {
    ACCEPT,
    REJECT,
    TIMEOUT
  }

  record OfferDecision(OfferAction action, Duration delay) {}
}
