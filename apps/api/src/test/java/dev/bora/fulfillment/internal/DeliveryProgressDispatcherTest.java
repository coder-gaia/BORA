package dev.bora.fulfillment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bora.couriers.CourierPositionsPersisted;
import dev.bora.couriers.PositionSample;
import dev.bora.routing.GeoPoint;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class DeliveryProgressDispatcherTest {
  @Test
  void overflowingEventCapacityBlocksThePublisherWithoutCallerRunsOrDiscard() throws Exception {
    overflow(2, 10, 1, 1, 1);
  }

  @Test
  void totalSampleCapacityAlsoBoundsRunningAndQueuedBatches() throws Exception {
    overflow(4, 4, 2, 2, 1);
  }

  private void overflow(
      int eventCapacity, int sampleCapacity, int firstSize, int secondSize, int thirdSize)
      throws Exception {
    var first = event(firstSize);
    var second = event(secondSize);
    var third = event(thirdSize);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    List<CourierPositionsPersisted> processed = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger concurrent = new AtomicInteger(), maximum = new AtomicInteger();
    var dispatcher =
        new DeliveryProgressDispatcher(
            event ->
                () -> {
                  int active = concurrent.incrementAndGet();
                  maximum.accumulateAndGet(active, Math::max);
                  try {
                    assertThat(Thread.currentThread().getName())
                        .startsWith("bora-delivery-progress-");
                    if (event == first) {
                      started.countDown();
                      await(release);
                    }
                    processed.add(event);
                  } finally {
                    concurrent.decrementAndGet();
                  }
                },
            eventCapacity,
            sampleCapacity);
    dispatcher.start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        dispatcher.positionsPersisted(first);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        dispatcher.positionsPersisted(second);
        var publishing = new CountDownLatch(1);
        var overflow =
            executor.submit(
                () -> {
                  publishing.countDown();
                  dispatcher.positionsPersisted(third);
                });
        assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> overflow.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(processed).isEmpty();
        release.countDown();
        overflow.get(5, TimeUnit.SECONDS);
        assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(processed).containsExactly(first, second, third);
        assertThat(maximum).hasValue(1);
      } finally {
        release.countDown();
      }
    } finally {
      dispatcher.stop();
    }
  }

  @Test
  void shutdownWaitsForAllAdmittedWorkAndDoesNotInterruptItsConsumer() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var processed = new AtomicInteger();
    var interrupted = new AtomicBoolean();
    var prepared = new AtomicInteger();
    var overflowPrepared = new CountDownLatch(1);
    var dispatcher =
        new DeliveryProgressDispatcher(
            event -> {
              if (prepared.incrementAndGet() == 3) overflowPrepared.countDown();
              return () -> {
                if (processed.get() == 0) {
                  started.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException failure) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                  }
                }
                processed.incrementAndGet();
              };
            },
            2,
            4);
    dispatcher.start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        dispatcher.positionsPersisted(event(1));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        dispatcher.positionsPersisted(event(1));
        var overflow = executor.submit(() -> dispatcher.positionsPersisted(event(1)));
        assertThat(overflowPrepared.await(5, TimeUnit.SECONDS)).isTrue();
        var stopped =
            executor.submit(
                () -> {
                  dispatcher.stop();
                });
        assertThatThrownBy(() -> stopped.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(processed).hasValue(0);
        release.countDown();
        overflow.get(5, TimeUnit.SECONDS);
        stopped.get(5, TimeUnit.SECONDS);
        assertThat(processed).hasValue(3);
        assertThat(interrupted).isFalse();
        assertThat(dispatcher.isRunning()).isFalse();
        assertThatThrownBy(() -> dispatcher.positionsPersisted(event(1)))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        release.countDown();
      }
    } finally {
      dispatcher.stop();
    }
  }

  @Test
  void interruptedOverflowPublisherStillHandsOffItsCommittedBatch() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var processed = new AtomicInteger();
    var restoredInterrupt = new AtomicBoolean();
    var dispatcher =
        new DeliveryProgressDispatcher(
            event ->
                () -> {
                  if (processed.get() == 0) {
                    started.countDown();
                    await(release);
                  }
                  processed.incrementAndGet();
                },
            1,
            2);
    dispatcher.start();
    Thread publisher = null;
    try {
      dispatcher.positionsPersisted(event(1));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      var publishing = new CountDownLatch(1);
      publisher =
          Thread.ofPlatform()
              .start(
                  () -> {
                    publishing.countDown();
                    dispatcher.positionsPersisted(event(1));
                    restoredInterrupt.set(Thread.currentThread().isInterrupted());
                  });
      assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
      publisher.interrupt();
      release.countDown();
      publisher.join(Duration.ofSeconds(5));
      assertThat(publisher.isAlive()).isFalse();
      assertThat(dispatcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
      assertThat(processed).hasValue(2);
      assertThat(restoredInterrupt).isTrue();
    } finally {
      release.countDown();
      if (publisher != null) publisher.join(Duration.ofSeconds(5));
      dispatcher.stop();
    }
  }

  private static CourierPositionsPersisted event(int count) {
    UUID courier = UUID.randomUUID();
    var samples =
        java.util.stream.IntStream.range(0, count)
            .mapToObj(
                index ->
                    new PositionSample(
                        Instant.EPOCH.plusSeconds(index), new GeoPoint(-23.56, -46.65)))
            .toList();
    return new CourierPositionsPersisted(Map.of(courier, samples), Map.of(courier, 3L));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Consumer was not released.");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }
}
