package dev.bora.simulator;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;

final class StoreActor implements Runnable {
  private final CityScenario scenario;
  private final CityScenario.Actor descriptor;
  private final SimulatorHttp.Actor api;
  private final SimulatorStats stats;
  private final ServerClock clock;
  private final BooleanSupplier running;
  private final SplittableRandom random;
  private final DemandCurve demand = new DemandCurve();
  private final String runId;
  private long sequence;

  StoreActor(
      CityScenario scenario,
      CityScenario.Actor descriptor,
      SimulatorHttp http,
      SimulatorStats stats,
      ServerClock clock,
      BooleanSupplier running,
      long seed,
      String runId) {
    this.scenario = scenario;
    this.descriptor = descriptor;
    this.api = http.actor("STORE", descriptor.index());
    this.stats = stats;
    this.clock = clock;
    this.running = running;
    this.random = DemandCurve.actorRandom(seed, "STORE", descriptor.index());
    this.runId = runId;
  }

  @Override
  public void run() {
    try {
      // As 40 identidades ficam prontas sem concentrar seu login no mesmo instante dos 120 GPS.
      pause(Duration.ofMillis(10_000 + descriptor.index() * 200L));
      while (running.getAsBoolean()) {
        try {
          api.get("/api/v1/auth/me").requireSuccess();
          break;
        } catch (SimulatorHttp.ApiFailure failure) {
          stats.count(failure.status == 429 ? "rateLimited" : "apiUnavailable");
          pause(failure.retryAfter);
        } catch (IOException failure) {
          pause(Duration.ofSeconds(5));
        }
      }
      // A primeira criação também segue a curva; não disparamos 40 pedidos no início.
      pause(nextOrder());
      while (running.getAsBoolean()) {
        GeoPoint dropoff = destination();
        String key = "sim:" + runId + ":" + descriptor.index() + ":" + sequence++;
        Map<String, Object> request = Map.of("dropoff", dropoff);
        boolean completed = false;
        while (running.getAsBoolean() && !completed) {
          try {
            SimulatorHttp.Reply reply = api.createOrder(request, key);
            if (reply.successful()) {
              stats.count("ordersCreated");
              completed = true;
            } else if (reply.status() == 429) {
              stats.count("rateLimited");
              pause(reply.retryAfter());
            } else if (reply.status() >= 500) {
              stats.count("apiUnavailable");
              pause(Duration.ofSeconds(5));
            } else {
              stats.count(reply.status() == 409 ? "stateConflicts" : "unexpectedHttpErrors");
              completed = true;
            }
          } catch (SimulatorHttp.ApiFailure failure) {
            stats.count(failure.status == 429 ? "rateLimited" : "apiUnavailable");
            pause(failure.retryAfter);
          } catch (IOException failure) {
            // A mesma chave e o mesmo destino recuperam a resposta mesmo se a criação já ocorreu.
            pause(Duration.ofSeconds(5));
          }
        }
        pause(nextOrder());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private Duration nextOrder() {
    var now = clock.instant();
    // Um perfil reduzido de CI não transfere a demanda de 40 lojas para uma única identidade.
    return demand.nextOrder(
        now, 40, demand.storeWeight(descriptor.index(), scenario.stores().size(), now), random);
  }

  private GeoPoint destination() {
    GeoPoint selected = scenario.destinations().get(random.nextInt(scenario.destinations().size()));
    for (int attempt = 0;
        attempt < 10 && descriptor.position().distanceTo(selected) < 300;
        attempt++) {
      selected = scenario.destinations().get(random.nextInt(scenario.destinations().size()));
    }
    return selected;
  }

  private void pause(Duration duration) throws InterruptedException {
    long until = System.nanoTime() + duration.toNanos();
    while (running.getAsBoolean() && System.nanoTime() < until) {
      Thread.sleep(
          Duration.ofMillis(Math.max(1, Math.min(200, (until - System.nanoTime()) / 1_000_000))));
    }
  }
}
