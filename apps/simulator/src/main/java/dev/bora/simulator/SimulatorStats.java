package dev.bora.simulator;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

final class SimulatorStats {
  private final Instant startedAt;
  private final LongSupplier monotonic;
  private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, String> couriers = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, Long> lastSeen = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, Integer> invalidResponses = new ConcurrentHashMap<>();
  private final AtomicLong lastSuccessfulRequest = new AtomicLong();
  private final AtomicLong inFlightHttp = new AtomicLong();
  private final AtomicLong peakInFlightHttp = new AtomicLong();
  private final AtomicLong maxHttpQueueMillis = new AtomicLong();
  private volatile boolean provisioned;
  private volatile int expectedCouriers;

  SimulatorStats(Instant startedAt) {
    this(startedAt, System::nanoTime);
  }

  SimulatorStats(Instant startedAt, LongSupplier monotonic) {
    this.startedAt = startedAt;
    this.monotonic = monotonic;
  }

  void count(String name) {
    counters.computeIfAbsent(name, ignored -> new LongAdder()).increment();
  }

  void add(String name, long amount) {
    counters.computeIfAbsent(name, ignored -> new LongAdder()).add(amount);
  }

  void response(int status) {
    count("http" + status);
    if (status >= 200 && status < 300) {
      lastSuccessfulRequest.set(monotonic.getAsLong());
    }
  }

  void requestStarted(long queuedNanos) {
    long active = inFlightHttp.incrementAndGet();
    peakInFlightHttp.accumulateAndGet(active, Math::max);
    maxHttpQueueMillis.accumulateAndGet(queuedNanos / 1_000_000, Math::max);
  }

  void requestFinished() {
    inFlightHttp.decrementAndGet();
  }

  void courier(int index, String status) {
    couriers.put(index, status);
    courierHeartbeat(index);
  }

  void courierHeartbeat(int index) {
    lastSeen.put(index, monotonic.getAsLong());
  }

  void invalidResponse(int index) {
    count("invalidApiResponses");
    invalidResponses.merge(index, 1, Integer::sum);
  }

  void healthyTick(int index) {
    invalidResponses.remove(index);
  }

  void provisioned(int expectedCouriers) {
    this.expectedCouriers = expectedCouriers;
    provisioned = true;
  }

  boolean healthy() {
    return provisioned
        && onlineCouriers() == expectedCouriers
        && lastSuccessfulRequest.get() != 0
        && counters.getOrDefault("fatalActorFailures", new LongAdder()).sum() == 0
        && invalidResponses.values().stream().noneMatch(count -> count >= 3)
        && monotonic.getAsLong() - lastSuccessfulRequest.get() < 30_000_000_000L;
  }

  Map<String, Object> snapshot(Instant now) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("startedAt", startedAt.toString());
    result.put("observedAt", now.toString());
    result.put("elapsedSeconds", java.time.Duration.between(startedAt, now).toSeconds());
    result.put("httpConcurrencyLimit", SimulatorHttp.MAX_IN_FLIGHT);
    result.put("httpInFlight", inFlightHttp.get());
    result.put("httpPeakInFlight", peakInFlightHttp.get());
    result.put("maxHttpQueueMillis", maxHttpQueueMillis.get());
    result.put("couriersKnown", couriers.size());
    result.put("couriersOnline", onlineCouriers());
    result.put("couriersExpected", expectedCouriers);
    result.put(
        "couriersWithPersistentContractErrors",
        invalidResponses.values().stream().filter(count -> count >= 3).count());
    Map<String, Long> byStatus = new java.util.TreeMap<>();
    couriers.values().forEach(status -> byStatus.merge(status, 1L, Long::sum));
    result.put("couriersByStatus", byStatus);
    Map<String, Long> values = new java.util.TreeMap<>();
    counters.forEach((name, value) -> values.put(name, value.sum()));
    result.put("counters", values);
    return result;
  }

  private long onlineCouriers() {
    long now = monotonic.getAsLong();
    return couriers.entrySet().stream()
        .filter(
            entry ->
                !entry.getValue().equals("OFFLINE")
                    && now - lastSeen.getOrDefault(entry.getKey(), 0L) < 30_000_000_000L)
        .count();
  }
}
