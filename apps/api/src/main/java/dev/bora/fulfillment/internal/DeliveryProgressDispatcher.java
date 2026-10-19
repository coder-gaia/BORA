package dev.bora.fulfillment.internal;

import dev.bora.couriers.CourierPositionsPersisted;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Keeps committed GPS batches off the persistence scheduler without discarding queued work. */
@Component
class DeliveryProgressDispatcher implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(DeliveryProgressDispatcher.class);
  private final Object lifecycle = new Object();
  private final Function<CourierPositionsPersisted, Runnable> prepare;
  private final int eventCapacity;
  private final int sampleCapacity;
  private final Semaphore events;
  private final Semaphore samples;
  private ThreadPoolExecutor executor;
  private volatile boolean running;
  private int submitting;

  @Autowired
  DeliveryProgressDispatcher(DeliveryProgress progress) {
    this(
        event -> {
          var captured = progress.capture(event);
          return () -> progress.positionsPersisted(captured);
        },
        16,
        40_000);
  }

  DeliveryProgressDispatcher(
      Function<CourierPositionsPersisted, Runnable> prepare,
      int eventCapacity,
      int sampleCapacity) {
    if (eventCapacity < 1 || sampleCapacity < 1) {
      throw new IllegalArgumentException("Progress queue capacities must be positive.");
    }
    this.prepare = prepare;
    this.eventCapacity = eventCapacity;
    this.sampleCapacity = sampleCapacity;
    this.events = new Semaphore(eventCapacity, true);
    this.samples = new Semaphore(sampleCapacity, true);
  }

  @EventListener
  public void positionsPersisted(CourierPositionsPersisted event) {
    if (event.positions().isEmpty()) return;
    int count =
        Math.toIntExact(event.positions().values().stream().mapToLong(java.util.List::size).sum());
    if (count > sampleCapacity) {
      throw new IllegalArgumentException(
          "A GPS batch exceeds the bounded progress queue capacity.");
    }
    synchronized (lifecycle) {
      if (!running) throw new IllegalStateException("Delivery progress is not running.");
      submitting++;
    }
    try {
      // Capture assignment/epoch before overflow can wait across delivery or reassignment.
      var task = prepare.apply(event);
      // Admission includes the running task. Overflow waits for capacity, never CallerRuns/discard.
      events.acquireUninterruptibly();
      samples.acquireUninterruptibly(count);
      try {
        executor.execute(
            () -> {
              try {
                task.run();
              } catch (RuntimeException failure) {
                LOG.warn(
                    "Delivery progress failed; database reconciliation remains active.", failure);
              } finally {
                samples.release(count);
                events.release();
                synchronized (lifecycle) {
                  lifecycle.notifyAll();
                }
              }
            });
      } catch (RuntimeException | Error failure) {
        samples.release(count);
        events.release();
        throw failure;
      }
    } finally {
      synchronized (lifecycle) {
        submitting--;
        lifecycle.notifyAll();
      }
    }
  }

  @Override
  public void start() {
    synchronized (lifecycle) {
      if (running) return;
      if (executor != null && !executor.isTerminated()) {
        throw new IllegalStateException("Delivery progress has not finished stopping.");
      }
      executor =
          new ThreadPoolExecutor(
              1,
              1,
              0,
              TimeUnit.MILLISECONDS,
              new ArrayBlockingQueue<>(eventCapacity),
              Thread.ofPlatform().name("bora-delivery-progress-", 0).factory(),
              new ThreadPoolExecutor.AbortPolicy());
      running = true;
    }
  }

  @Override
  public void stop() {
    boolean interrupted = false;
    ThreadPoolExecutor draining;
    synchronized (lifecycle) {
      running = false;
      // The app scheduler stops in a higher phase, so its in-progress publications finish first.
      while (submitting > 0) {
        try {
          lifecycle.wait();
        } catch (InterruptedException failure) {
          interrupted = true;
        }
      }
      draining = executor;
      if (draining != null) draining.shutdown();
    }
    if (draining != null) {
      while (!draining.isTerminated()) {
        try {
          draining.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
          interrupted = true;
        }
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isPauseable() {
    return false;
  }

  @Override
  public int getPhase() {
    return 0;
  }

  boolean awaitIdle(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (lifecycle) {
      while (submitting > 0 || events.availablePermits() != eventCapacity) {
        long left = deadline - System.nanoTime();
        if (left <= 0) return false;
        TimeUnit.NANOSECONDS.timedWait(lifecycle, left);
      }
      return true;
    }
  }
}
