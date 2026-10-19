package dev.bora.simulator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.LongSupplier;

/** Usa o relógio da API e tempo monotônico local, inclusive com o notebook em outro fuso. */
final class ServerClock {
  private final LongSupplier nanoTime;
  private volatile Sample sample;

  ServerClock(LongSupplier nanoTime) {
    this.nanoTime = nanoTime;
  }

  synchronized void synchronize(Instant serverTime) {
    // Uma resposta mais lenta de outra conexão não deve fazer o relógio andar para trás.
    Instant chosen = sample == null || serverTime.isAfter(instant()) ? serverTime : instant();
    sample = new Sample(chosen, nanoTime.getAsLong());
  }

  Instant instant() {
    Sample current = sample;
    if (current == null) {
      throw new IllegalStateException("A API ainda não informou seu relógio.");
    }
    return current.time().plusNanos(Math.max(0, nanoTime.getAsLong() - current.nanoTime()));
  }

  synchronized Instant nextPosition(Instant previous) {
    Instant now = instant().truncatedTo(ChronoUnit.MICROS);
    return previous != null && !now.isAfter(previous) ? previous.plusNanos(1_000) : now;
  }

  private record Sample(Instant time, long nanoTime) {}
}
