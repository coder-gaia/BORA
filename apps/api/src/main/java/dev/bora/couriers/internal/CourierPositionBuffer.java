package dev.bora.couriers.internal;

import dev.bora.couriers.CourierPositionsPersisted;
import dev.bora.couriers.PositionReceipt;
import dev.bora.couriers.PositionSample;
import dev.bora.shared.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
class CourierPositionBuffer {
  private static final Logger LOG = LoggerFactory.getLogger(CourierPositionBuffer.class);
  private static final int MAX_COURIERS = 2_000;
  private static final int MAX_POINTS = 40_000;
  private static final int MAX_POINTS_PER_COURIER = 400;
  private final Map<UUID, Pending> pending = new HashMap<>();
  private final Map<UUID, Pending> inFlight = new HashMap<>();
  private final AtomicBoolean flushing = new AtomicBoolean();
  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Clock clock;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transaction;
  private int bufferedPoints;
  private long flushFailures;
  private long maxPersistLagMillis;
  private Instant lastFlushAt;

  CourierPositionBuffer(
      JdbcClient jdbc,
      Clock clock,
      ApplicationEventPublisher events,
      PlatformTransactionManager manager,
      JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    this.events = events;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setTimeout(5);
  }

  synchronized PositionReceipt enqueue(
      UUID courier, List<PositionSample> samples, Instant persistedAt, long epoch) {
    Pending previous = pending.get(courier);
    Pending draining = inFlight.get(courier);
    if ((previous != null && previous.epoch() > epoch)
        || (draining != null && draining.epoch() > epoch)) {
      return new PositionReceipt(0, samples.size(), persistedAt, clock.instant());
    }
    if (previous != null && previous.epoch() < epoch) {
      bufferedPoints -= previous.samples().size();
      pending.remove(courier);
      previous = null;
    }
    Instant newest = persistedAt;
    if (previous != null) newest = later(newest, previous.latest().recordedAt());
    if (draining != null && draining.epoch() == epoch)
      newest = later(newest, draining.latest().recordedAt());
    List<PositionSample> accepted = new ArrayList<>();
    for (PositionSample sample : samples) {
      if (newest == null || sample.recordedAt().isAfter(newest)) {
        accepted.add(sample);
      }
    }
    if (accepted.isEmpty()) {
      return new PositionReceipt(0, samples.size(), newest, clock.instant());
    }
    Set<UUID> couriers = new HashSet<>(pending.keySet());
    couriers.addAll(inFlight.keySet());
    int existingCount =
        (previous == null ? 0 : previous.samples().size())
            + (draining == null ? 0 : draining.samples().size());
    if ((!couriers.contains(courier) && couriers.size() >= MAX_COURIERS)
        || existingCount + accepted.size() > MAX_POINTS_PER_COURIER
        || bufferedPoints + accepted.size() > MAX_POINTS) {
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "POSITION_BUFFER_FULL",
          "Aguarde a gravação das posições antes de enviar outro lote.",
          1L);
    }
    Instant oldest = previous == null ? clock.instant() : previous.queuedAt();
    if (Duration.between(oldestQueuedAt(), clock.instant()).toMillis() >= 1_000) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "POSITION_STORAGE_DELAYED",
          "A gravação das posições está atrasada. Tente novamente em instantes.",
          1L);
    }
    List<PositionSample> merged = new ArrayList<>();
    if (previous != null) merged.addAll(previous.samples());
    merged.addAll(accepted);
    pending.put(courier, new Pending(List.copyOf(merged), oldest, epoch));
    bufferedPoints += accepted.size();
    return new PositionReceipt(
        accepted.size(),
        samples.size() - accepted.size(),
        accepted.getLast().recordedAt(),
        oldest.plusSeconds(1));
  }

  @Scheduled(
      fixedDelayString = "${bora.couriers.flush-delay-ms:250}",
      initialDelayString = "${bora.couriers.flush-initial-delay-ms:250}")
  public void flush() {
    if (!flushing.compareAndSet(false, true)) return;
    Map<UUID, Pending> snapshot = drain();
    try {
      if (snapshot.isEmpty()) return;
      Map<UUID, List<PositionSample>> persisted;
      try {
        persisted = transaction.execute(status -> persist(snapshot));
      } catch (RuntimeException failure) {
        restore(snapshot);
        LOG.warn(
            "A gravação das posições falhou; o lote foi preservado para nova tentativa.", failure);
        return;
      }
      complete(snapshot);
      if (persisted != null && !persisted.isEmpty()) {
        try {
          Map<UUID, Long> epochs = new HashMap<>();
          persisted.keySet().forEach(id -> epochs.put(id, snapshot.get(id).epoch()));
          events.publishEvent(new CourierPositionsPersisted(persisted, epochs));
        } catch (RuntimeException failure) {
          LOG.warn(
              "As posições foram gravadas, mas o aviso falhou. A reconciliação usará o banco.",
              failure);
        }
      }
    } finally {
      flushing.set(false);
    }
  }

  synchronized Metrics metrics() {
    Set<UUID> couriers = new HashSet<>(pending.keySet());
    couriers.addAll(inFlight.keySet());
    return new Metrics(
        couriers.size(),
        bufferedPoints,
        oldestQueuedAt(),
        flushFailures,
        maxPersistLagMillis,
        lastFlushAt);
  }

  private synchronized Map<UUID, Pending> drain() {
    Map<UUID, Pending> snapshot = new LinkedHashMap<>();
    pending.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> snapshot.put(entry.getKey(), entry.getValue()));
    pending.clear();
    inFlight.putAll(snapshot);
    return snapshot;
  }

  private Map<UUID, List<PositionSample>> persist(Map<UUID, Pending> snapshot) {
    var input =
        snapshot.entrySet().stream()
            .map(
                entry -> {
                  PositionSample latest = entry.getValue().latest();
                  return new PositionRow(
                      entry.getKey(),
                      entry.getValue().epoch(),
                      latest.recordedAt().toString(),
                      latest.position().latitude(),
                      latest.position().longitude());
                })
            .toList();
    var updated =
        jdbc.sql(
                """
        WITH previous AS MATERIALIZED (
          SELECT c.id,c.location_updated_at previous_time,i.recorded,i.lat,i.lon
          FROM jsonb_to_recordset(CAST(:batch AS jsonb)) AS
            i(id uuid,epoch bigint,recorded timestamptz,lat double precision,lon double precision)
          JOIN couriers c ON c.id=i.id
          WHERE c.status<>'OFFLINE' AND c.position_epoch=i.epoch
          ORDER BY c.id FOR UPDATE OF c
        )
        UPDATE couriers c SET
          location=ST_SetSRID(ST_MakePoint(p.lon,p.lat),4326)::geography,
          location_updated_at=p.recorded
        FROM previous p WHERE c.id=p.id AND
          (p.previous_time IS NULL OR p.previous_time<p.recorded)
        RETURNING c.id,p.previous_time
        """)
            .param("batch", json.writeValueAsString(input))
            .query(
                (row, index) -> {
                  var previous = row.getTimestamp("previous_time");
                  return new PersistedPosition(
                      row.getObject("id", UUID.class),
                      previous == null ? null : previous.toInstant());
                })
            .list();
    Map<UUID, List<PositionSample>> persisted = new LinkedHashMap<>();
    for (PersistedPosition changed : updated) {
      Instant before = changed.previousTime();
      persisted.put(
          changed.id(),
          snapshot.get(changed.id()).samples().stream()
              .filter(sample -> before == null || sample.recordedAt().isAfter(before))
              .toList());
    }
    return persisted;
  }

  private synchronized void complete(Map<UUID, Pending> snapshot) {
    Instant now = clock.instant();
    snapshot.forEach(
        (id, batch) -> {
          bufferedPoints -= batch.samples().size();
          inFlight.remove(id);
          maxPersistLagMillis =
              Math.max(maxPersistLagMillis, Duration.between(batch.queuedAt(), now).toMillis());
        });
    lastFlushAt = now;
  }

  private synchronized void restore(Map<UUID, Pending> snapshot) {
    flushFailures++;
    snapshot.forEach(
        (id, failed) -> {
          inFlight.remove(id);
          Pending newer = pending.get(id);
          if (newer == null) {
            pending.put(id, failed);
          } else if (newer.epoch() > failed.epoch()) {
            bufferedPoints -= failed.samples().size();
          } else {
            TreeMap<Instant, PositionSample> merged = new TreeMap<>();
            failed.samples().forEach(sample -> merged.put(sample.recordedAt(), sample));
            newer.samples().forEach(sample -> merged.put(sample.recordedAt(), sample));
            bufferedPoints -= failed.samples().size() + newer.samples().size() - merged.size();
            pending.put(
                id, new Pending(List.copyOf(merged.values()), failed.queuedAt(), failed.epoch()));
          }
        });
  }

  private Instant oldestQueuedAt() {
    return java.util.stream.Stream.concat(pending.values().stream(), inFlight.values().stream())
        .map(Pending::queuedAt)
        .min(Comparator.naturalOrder())
        .orElseGet(clock::instant);
  }

  private static Instant later(Instant first, Instant second) {
    return first == null || second.isAfter(first) ? second : first;
  }

  record Metrics(
      int pendingCouriers,
      int bufferedPoints,
      Instant oldestQueuedAt,
      long flushFailures,
      long maxPersistLagMillis,
      Instant lastFlushAt) {}

  private record Pending(List<PositionSample> samples, Instant queuedAt, long epoch) {
    PositionSample latest() {
      return samples.getLast();
    }
  }

  private record PositionRow(UUID id, long epoch, String recorded, double lat, double lon) {}

  private record PersistedPosition(UUID id, Instant previousTime) {}
}
