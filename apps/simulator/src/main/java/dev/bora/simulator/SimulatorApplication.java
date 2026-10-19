package dev.bora.simulator;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SimulatorApplication {
  private SimulatorApplication() {}

  public static void main(String[] args) throws Exception {
    SimulatorConfig config = SimulatorConfig.from(System.getenv());
    AtomicBoolean running = new AtomicBoolean(true);
    CountDownLatch finished = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  running.set(false);
                  try {
                    finished.await(25, TimeUnit.SECONDS);
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                },
                "simulator-shutdown"));
    try {
      run(config, running);
    } finally {
      finished.countDown();
    }
  }

  private static void run(SimulatorConfig config, AtomicBoolean running) throws Exception {
    ServerClock clock = new ServerClock(System::nanoTime);
    SimulatorStats stats = new SimulatorStats(Instant.now());
    try (SimulatorHttp http = new SimulatorHttp(config, stats, clock)) {
      CityScenario scenario = bootstrap(http, running);
      if (scenario == null) {
        return;
      }
      if (scenario.couriers().size() < config.courierCount()
          || scenario.stores().size() < config.storeCount()) {
        throw new IllegalStateException(
            "O cenário não tem todos os entregadores e lojas solicitados.");
      }
      if (scenario.seed() != config.seed()) {
        throw new IllegalStateException("SIM_SEED deve ter o mesmo valor na API e no simulador.");
      }
      scenario =
          new CityScenario(
              scenario.seed(),
              scenario.speed(),
              scenario.serverTime(),
              scenario.couriers().subList(0, config.courierCount()),
              scenario.stores().subList(0, config.storeCount()),
              scenario.destinations());
      clock.synchronize(scenario.serverTime());
      stats.provisioned(config.courierCount());
      HttpServer health = health(config, stats);
      ExecutorService actors = Executors.newVirtualThreadPerTaskExecutor();
      String runId = UUID.randomUUID().toString();
      for (var actor : scenario.couriers()) {
        actors.execute(
            guard(
                new CourierActor(scenario, actor, http, stats, clock, running::get, config.seed()),
                stats,
                running));
      }
      for (var actor : scenario.stores()) {
        actors.execute(
            guard(
                new StoreActor(
                    scenario, actor, http, stats, clock, running::get, config.seed(), runId),
                stats,
                running));
      }
      long started = System.nanoTime();
      long nextReport = started;
      long nextClockSync = started + Duration.ofMinutes(5).toNanos();
      try {
        while (running.get()
            && (config.duration().isZero()
                || System.nanoTime() - started < config.duration().toNanos())) {
          if (System.nanoTime() >= nextReport) {
            System.out.println(
                SimulatorHttp.JSON.writeValueAsString(stats.snapshot(Instant.now())));
            nextReport = System.nanoTime() + Duration.ofMinutes(1).toNanos();
          }
          if (System.nanoTime() >= nextClockSync) {
            try {
              http.scenario().requireSuccess();
            } catch (IOException failure) {
              stats.count("clockRefreshFailures");
            }
            nextClockSync = System.nanoTime() + Duration.ofMinutes(5).toNanos();
          }
          Thread.sleep(Duration.ofSeconds(1));
        }
      } finally {
        running.set(false);
        actors.shutdown();
        if (!actors.awaitTermination(12, TimeUnit.SECONDS)) {
          actors.shutdownNow();
          actors.awaitTermination(3, TimeUnit.SECONDS);
        }
        health.stop(0);
        String report =
            SimulatorHttp.JSON
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(stats.snapshot(Instant.now()));
        System.out.println(report);
        if (config.reportPath() != null) {
          Files.writeString(config.reportPath(), report + "\n", StandardCharsets.UTF_8);
        }
      }
    }
  }

  private static Runnable guard(Runnable actor, SimulatorStats stats, AtomicBoolean running) {
    return () -> {
      try {
        actor.run();
      } catch (RuntimeException failure) {
        stats.count("fatalActorFailures");
        running.set(false);
        System.err.println("Um ator do simulador foi interrompido por uma falha interna.");
      }
    };
  }

  private static CityScenario bootstrap(SimulatorHttp http, AtomicBoolean running)
      throws InterruptedException {
    while (running.get()) {
      try {
        return CityScenario.parse(http.scenario().requireSuccess());
      } catch (SimulatorHttp.ApiFailure failure) {
        if (failure.status == 401 || failure.status == 403) {
          throw new IllegalStateException("A chave do simulador foi recusada pela API.");
        }
        Thread.sleep(Duration.ofSeconds(5));
      } catch (IOException failure) {
        Thread.sleep(Duration.ofSeconds(5));
      }
    }
    return null;
  }

  private static HttpServer health(SimulatorConfig config, SimulatorStats stats)
      throws IOException {
    HttpServer server =
        HttpServer.create(new InetSocketAddress("127.0.0.1", config.healthPort()), 0);
    server.createContext(
        "/health",
        exchange -> {
          boolean healthy = stats.healthy();
          var status = new java.util.LinkedHashMap<String, Object>(stats.snapshot(Instant.now()));
          status.put("status", healthy ? "UP" : "DOWN");
          byte[] response = SimulatorHttp.JSON.writeValueAsBytes(status);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(healthy ? 200 : 503, response.length);
          try (var output = exchange.getResponseBody()) {
            output.write(response);
          }
        });
    server.createContext(
        "/status",
        exchange -> {
          byte[] response = SimulatorHttp.JSON.writeValueAsBytes(stats.snapshot(Instant.now()));
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          try (var output = exchange.getResponseBody()) {
            output.write(response);
          }
        });
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }
}
