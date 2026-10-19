package dev.bora.simulator;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class HttpConcurrencyTest {
  @Test
  @Timeout(15)
  void realHttpNeverExceedsFourInFlightRequestsAndDoesNotDropQueuedWork() throws Exception {
    try (Server server = new Server();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        SimulatorHttp http = server.client()) {
      server.delayMillis = 100;
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Integer>> requests = new ArrayList<>();
      for (int index = 0; index < 32; index++) {
        requests.add(
            executor.submit(
                () -> {
                  start.await();
                  return http.scenario().status();
                }));
      }
      start.countDown();
      for (var response : requests) assertEquals(200, response.get(10, TimeUnit.SECONDS));
      assertEquals(32, server.completed.get());
      assertTrue(server.peak.get() > 1);
      assertTrue(server.peak.get() <= 4);
      assertEquals(0L, server.stats.snapshot(Instant.now()).get("httpInFlight"));
      assertEquals(4L, server.stats.snapshot(Instant.now()).get("httpPeakInFlight"));
    }
  }

  @Test
  @Timeout(15)
  void interruptionWhileQueuedAndWhileSendingReleasesAllPermits() throws Exception {
    try (Server server = new Server();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        SimulatorHttp http = server.client()) {
      CountDownLatch firstGate = new CountDownLatch(1);
      server.gate.set(firstGate);
      List<Future<Integer>> requests = new ArrayList<>();
      try {
        for (int index = 0; index < 4; index++)
          requests.add(executor.submit(() -> http.scenario().status()));
        await(() -> server.active.get() == 4);
        Future<Integer> queued = executor.submit(() -> http.scenario().status());
        requests.add(queued);
        Thread.sleep(50);
        assertTrue(queued.cancel(true));
        assertTrue(requests.getFirst().cancel(true));
        firstGate.countDown();
        for (var response : requests) {
          if (!response.isCancelled()) assertEquals(200, response.get(5, TimeUnit.SECONDS));
        }
        await(() -> server.active.get() == 0);
        CountDownLatch secondGate = new CountDownLatch(1);
        server.gate.set(secondGate);
        for (int index = 0; index < 4; index++)
          requests.add(executor.submit(() -> http.scenario().status()));
        await(() -> server.active.get() == 4);
        secondGate.countDown();
        for (var response : requests) {
          if (!response.isCancelled()) assertEquals(200, response.get(5, TimeUnit.SECONDS));
        }
        assertEquals(0L, server.stats.snapshot(Instant.now()).get("httpInFlight"));
      } finally {
        server.gate.get().countDown();
        firstGate.countDown();
        requests.forEach(request -> request.cancel(true));
      }
    }
  }

  @Test
  @Timeout(10)
  void repeatedTransportFailuresDoNotConsumeConcurrencyPermanently() throws Exception {
    try (Server server = new Server();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        SimulatorHttp http = server.client()) {
      server.breakResponse.set(true);
      for (int index = 0; index < 8; index++) assertThrows(IOException.class, http::scenario);
      server.breakResponse.set(false);
      Future<Integer> followup = executor.submit(() -> http.scenario().status());
      try {
        assertEquals(200, followup.get(3, TimeUnit.SECONDS));
      } finally {
        followup.cancel(true);
      }
      assertEquals(0L, server.stats.snapshot(Instant.now()).get("httpInFlight"));
    }
  }

  private static void await(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(condition.getAsBoolean());
  }

  private static final class Server implements AutoCloseable {
    final HttpServer server;
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger peak = new AtomicInteger();
    final AtomicInteger completed = new AtomicInteger();
    final AtomicBoolean breakResponse = new AtomicBoolean();
    final AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(0));
    final SimulatorStats stats = new SimulatorStats(Instant.now());
    long delayMillis;

    Server() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/",
          exchange -> {
            int count = active.incrementAndGet();
            peak.accumulateAndGet(count, Math::max);
            try {
              gate.get().await();
              if (delayMillis > 0) Thread.sleep(delayMillis);
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              if (breakResponse.get()) {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write(1);
              } else {
                byte[] bytes =
                    "{\"serverTime\":\"2026-10-19T12:00:00Z\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
              }
              completed.incrementAndGet();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } finally {
              active.decrementAndGet();
              exchange.close();
            }
          });
      server.start();
    }

    SimulatorHttp client() {
      var config =
          new SimulatorConfig(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              "test-only-key-0123456789abcdef0123456789",
              2026,
              Duration.ZERO,
              8090,
              1,
              1,
              false,
              null);
      return new SimulatorHttp(config, stats, new ServerClock(System::nanoTime));
    }

    @Override
    public void close() {
      gate.get().countDown();
      server.stop(0);
      executor.close();
    }
  }
}
