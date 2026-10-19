package dev.bora.simulator;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;

/** Uma thread virtual por entregador; o estado e as versões sempre vêm da API. */
final class CourierActor implements Runnable {
  private static final long POSITION_INTERVAL = Duration.ofSeconds(2).toNanos();
  private final CityScenario scenario;
  private final CityScenario.Actor descriptor;
  private final SimulatorHttp.Actor api;
  private final SimulatorStats stats;
  private final ServerClock clock;
  private final BooleanSupplier running;
  private final SplittableRandom random;
  private GeoPoint position;
  private Instant lastPositionTime;
  private JsonNode work;
  private RouteMotion motion;
  private String motionOrder;
  private String motionDestination;
  private String offerId;
  private DemandCurve.OfferDecision decision;
  private long decisionAt;
  private long waitAtStoreUntil;
  private long nextWork;
  private long nextInbox;
  private long nextIdleRoute;
  private long nextPosition;

  CourierActor(
      CityScenario scenario,
      CityScenario.Actor descriptor,
      SimulatorHttp http,
      SimulatorStats stats,
      ServerClock clock,
      BooleanSupplier running,
      long seed) {
    this.scenario = scenario;
    this.descriptor = descriptor;
    this.api = http.actor("COURIER", descriptor.index());
    this.stats = stats;
    this.clock = clock;
    this.running = running;
    this.random = DemandCurve.actorRandom(seed, "COURIER", descriptor.index());
    this.position = descriptor.position();
  }

  @Override
  public void run() {
    try {
      pause(Duration.ofMillis(descriptor.index() * 55L));
      while (running.getAsBoolean()) {
        try {
          tick();
          stats.healthyTick(descriptor.index());
          pause(Duration.ofMillis(200));
        } catch (SimulatorHttp.ApiFailure failure) {
          handle(failure);
          pause(failure.status == 429 ? failure.retryAfter : Duration.ofSeconds(2));
        } catch (IOException failure) {
          // Após uma resposta perdida, a próxima iteração reconcilia a ação com GET work.
          nextWork = 0;
          nextInbox = 0;
          pause(Duration.ofSeconds(2));
        } catch (IllegalArgumentException | IllegalStateException failure) {
          stats.invalidResponse(descriptor.index());
          nextWork = 0;
          pause(Duration.ofSeconds(5));
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void tick() throws IOException, InterruptedException {
    long now = System.nanoTime();
    if (now >= nextWork) {
      refreshWork(now);
    }
    if (work == null) {
      return;
    }
    String status = work.path("courier").path("status").asString();
    if (status.equals("OFFLINE")) {
      JsonNode online =
          api.post("/api/v1/courier/availability", Map.of("online", true, "position", position))
              .requireSuccess();
      position = CityScenario.point(online.path("location"));
      stats.courier(descriptor.index(), online.path("status").asString());
      // O relógio é obtido depois da transição online para não enviar amostra anterior à época.
      api.get("/api/v1/courier/offer").requireSuccess();
      refreshWork(System.nanoTime());
      nextPosition = 0;
    }
    if (motion != null) {
      position = motion.at(System.nanoTime());
    }
    if (now >= nextPosition) {
      sendPosition();
      // Sem rajadas para recuperar ticks perdidos por rede ou por suspensão do processo.
      nextPosition = now + POSITION_INTERVAL;
    }
    // A rota exige uma posição posterior à atribuição. Envie-a antes de consultar a rota;
    // um 409 enquanto o lote aguarda flush não deve impedir as próximas posições.
    JsonNode order = work.path("order");
    if (!order.isMissingNode() && !order.isNull()) {
      activeOrder(order, now);
    } else {
      idle(now);
    }
  }

  private void refreshWork(long now) throws IOException, InterruptedException {
    JsonNode refreshed = api.get("/api/v1/courier/work").requireSuccess();
    JsonNode courier = refreshed.path("courier");
    String courierStatus = courier.path("status").asString("");
    if (!courier.hasNonNull("id")
        || !java.util.Set.of("OFFLINE", "AVAILABLE", "OFFERED", "BUSY").contains(courierStatus)
        || (!courierStatus.equals("OFFLINE") && !courier.hasNonNull("location"))) {
      throw new IllegalArgumentException("A API não informou o estado válido do entregador.");
    }
    JsonNode order = refreshed.path("order");
    if (!order.isMissingNode()
        && !order.isNull()
        && (!order.hasNonNull("id")
            || !order.hasNonNull("version")
            || !java.util.Set.of("ASSIGNED", "AT_STORE", "PICKED_UP")
                .contains(order.path("status").asString("")))) {
      throw new IllegalArgumentException("A API não informou o trabalho válido do entregador.");
    }
    stats.courier(descriptor.index(), courierStatus);
    if (work == null && refreshed.path("courier").hasNonNull("location")) {
      position = CityScenario.point(refreshed.path("courier").path("location"));
    }
    String previous = work == null ? "" : work.path("order").path("id").asString("");
    String current = refreshed.path("order").path("id").asString("");
    if (!previous.equals(current)) {
      motion = null;
      motionOrder = null;
      motionDestination = null;
      waitAtStoreUntil = 0;
    }
    work = refreshed;
    nextWork = now + Duration.ofSeconds(current.isEmpty() ? 30 : 6).toNanos();
  }

  private void activeOrder(JsonNode order, long now) throws IOException, InterruptedException {
    offerId = null;
    String id = order.path("id").asString();
    String state = order.path("status").asString();
    switch (state) {
      case "ASSIGNED" -> route(id, "PICKUP", now);
      case "AT_STORE" -> {
        if (waitAtStoreUntil == 0) {
          waitAtStoreUntil = now + DemandCurve.storeWait(random, scenario.speed()).toNanos();
        }
        if (now >= waitAtStoreUntil) {
          SimulatorHttp.Reply reply =
              api.post(
                  "/api/v1/orders/" + id + "/pickup",
                  Map.of("expectedVersion", order.path("version").asLong()));
          if (reply.successful()) {
            stats.count("pickups");
            motion = null;
            motionDestination = null;
          }
          reconcile(reply);
        }
      }
      case "PICKED_UP" -> {
        route(id, "DROPOFF", now);
        if (motion != null && motion.finished(now)) {
          if (order.path("confirmationRequired").asBoolean()) {
            // Não há código privado disponível para o simulador; a API deve isolar a oferta.
            stats.count("visitorAssignmentsUnsupported");
            nextWork = now + Duration.ofSeconds(30).toNanos();
            return;
          }
          SimulatorHttp.Reply reply =
              api.post(
                  "/api/v1/orders/" + id + "/deliver",
                  Map.of("expectedVersion", order.path("version").asLong()));
          if (reply.successful()) {
            stats.count("deliveries");
            motion = null;
          }
          reconcile(reply);
        }
      }
      default -> nextWork = 0;
    }
  }

  private void route(String id, String destination, long now)
      throws IOException, InterruptedException {
    if (motion == null || !id.equals(motionOrder) || !destination.equals(motionDestination)) {
      JsonNode route = api.get("/api/v1/courier/route").requireSuccess();
      if (!id.equals(route.path("orderId").asString())
          || !destination.equals(route.path("destination").asString())) {
        nextWork = 0;
        return;
      }
      motion = CityScenario.motion(route, now);
      motionOrder = id;
      motionDestination = destination;
    }
  }

  private void idle(long now) throws IOException, InterruptedException {
    if (now >= nextInbox) {
      JsonNode inbox = api.get("/api/v1/courier/offer").requireSuccess();
      JsonNode offer = inbox.path("offer");
      String current = offer.path("id").asString("");
      stats.courier(descriptor.index(), current.isEmpty() ? "AVAILABLE" : "OFFERED");
      if (current.isEmpty()) {
        offerId = null;
        decision = null;
      } else if (!current.equals(offerId)) {
        offerId = current;
        decision = DemandCurve.offerDecision(random);
        decisionAt = now + decision.delay().toNanos();
        motion = null;
        stats.count("offers");
        if (decision.action() == DemandCurve.OfferAction.TIMEOUT) {
          stats.count("offerTimeoutDecisions");
        }
      }
      nextInbox = now + Duration.ofSeconds(4).toNanos();
    }
    if (offerId != null) {
      if (decision != null
          && now >= decisionAt
          && decision.action() != DemandCurve.OfferAction.TIMEOUT) {
        boolean accept = decision.action() == DemandCurve.OfferAction.ACCEPT;
        SimulatorHttp.Reply reply =
            api.post("/api/v1/offers/" + offerId + (accept ? "/accept" : "/reject"), null);
        if (reply.successful()) {
          stats.count(accept ? "acceptedOffers" : "rejectedOffers");
        }
        decision = null;
        nextInbox = 0;
        reconcile(reply);
      }
      return;
    }
    if ((motion == null || motion.finished(now)) && now >= nextIdleRoute) {
      GeoPoint destination = chooseDemandRegion();
      if (position.distanceTo(destination) > 100) {
        JsonNode route =
            api.post(
                    "/api/v1/simulator/route",
                    Map.of("origin", position, "destination", destination))
                .requireSuccess();
        motion = CityScenario.motion(route, now);
        motionOrder = null;
        motionDestination = "IDLE";
      }
      nextIdleRoute = now + Duration.ofSeconds(15 + random.nextInt(16)).toNanos();
    }
  }

  private GeoPoint chooseDemandRegion() {
    // O mesmo peso usado pelas lojas atrai os livres à região com mais pedidos previstos.
    DemandCurve demand = new DemandCurve();
    var now = clock.instant();
    double total =
        scenario.stores().stream()
            .mapToDouble(store -> demand.storeWeight(store.index(), scenario.stores().size(), now))
            .sum();
    double draw = random.nextDouble(total);
    for (var store : scenario.stores()) {
      draw -= demand.storeWeight(store.index(), scenario.stores().size(), now);
      if (draw <= 0) {
        return store.position();
      }
    }
    return scenario.stores().getLast().position();
  }

  private void sendPosition() throws IOException, InterruptedException {
    Instant recorded = clock.nextPosition(lastPositionTime);
    SimulatorHttp.Reply reply =
        api.post(
            "/api/v1/courier/positions",
            Map.of(
                "positions",
                java.util.List.of(
                    Map.of("recordedAt", recorded.toString(), "position", position))));
    if (reply.status() == 202) {
      if (!reply.body().hasNonNull("accepted") || !reply.body().hasNonNull("ignored")) {
        throw new IllegalArgumentException("A API não informou um recibo de posição válido.");
      }
      lastPositionTime = recorded;
      stats.courierHeartbeat(descriptor.index());
      stats.add("acceptedPositions", reply.body().path("accepted").asLong());
      stats.add("stalePositions", reply.body().path("ignored").asLong());
    } else {
      nextWork = 0;
      reply.requireSuccess();
      throw new IllegalArgumentException("A API não confirmou a posição com HTTP 202.");
    }
  }

  private void reconcile(SimulatorHttp.Reply reply) throws IOException, InterruptedException {
    nextWork = 0;
    if (reply.successful() || reply.status() == 404 || reply.status() == 409) {
      if (!reply.successful()) {
        stats.count("stateConflicts");
      }
      refreshWork(System.nanoTime());
      return;
    }
    reply.requireSuccess();
  }

  private void handle(SimulatorHttp.ApiFailure failure) {
    nextWork = 0;
    nextInbox = 0;
    if (failure.status == 404 || failure.status == 409) {
      stats.count("stateConflicts");
    } else if (failure.status == 429) {
      stats.count("rateLimited");
    } else if (failure.status >= 500) {
      stats.count("apiUnavailable");
    } else {
      stats.count("unexpectedHttpErrors");
    }
  }

  private void pause(Duration duration) throws InterruptedException {
    long until = System.nanoTime() + duration.toNanos();
    while (running.getAsBoolean() && System.nanoTime() < until) {
      Thread.sleep(
          Duration.ofMillis(Math.max(1, Math.min(200, (until - System.nanoTime()) / 1_000_000))));
    }
  }
}
