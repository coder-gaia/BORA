package dev.bora.simulator;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CourierActorTest {
  @Test
  void keepsSendingPositionsWhenRouteWaitsForFirstPostAssignmentPosition() throws Exception {
    AtomicInteger positions = new AtomicInteger();
    AtomicInteger routes = new AtomicInteger();
    AtomicBoolean running = new AtomicBoolean(true);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          String response =
              switch (path) {
                case "/api/v1/auth/csrf" -> "{\"headerName\":\"X-CSRF-TOKEN\",\"token\":\"test\"}";
                case "/api/v1/auth/simulator" -> "{\"authenticated\":true}";
                case "/api/v1/courier/work" ->
                    "{\"courier\":{\"id\":\"courier\",\"status\":\"BUSY\",\"location\":{\"latitude\":-23.56,\"longitude\":-46.65}},\"order\":{\"id\":\"order\",\"status\":\"ASSIGNED\",\"version\":1}}";
                case "/api/v1/courier/positions" -> {
                  positions.incrementAndGet();
                  yield "{\"accepted\":1,\"ignored\":0}";
                }
                case "/api/v1/courier/route" -> {
                  if (positions.get() == 0) {
                    yield "{\"code\":\"COURIER_POSITION_STALE\"}";
                  }
                  routes.incrementAndGet();
                  yield "{\"orderId\":\"order\",\"destination\":\"PICKUP\",\"geometry\":[{\"latitude\":-23.56,\"longitude\":-46.65},{\"latitude\":-23.55,\"longitude\":-46.65}],\"simulatedDurationMillis\":20000}";
                }
                default -> "{}";
              };
          int status =
              path.endsWith("positions")
                  ? 202
                  : path.endsWith("route") && positions.get() == 0 ? 409 : 200;
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
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
    var stats = new SimulatorStats(Instant.now());
    var clock = new ServerClock(System::nanoTime);
    clock.synchronize(Instant.parse("2026-10-19T12:00:00Z"));
    GeoPoint point = new GeoPoint(-23.56, -46.65);
    var descriptor = new CityScenario.Actor(0, "courier", point);
    var scenario =
        new CityScenario(
            2026,
            3,
            clock.instant(),
            List.of(descriptor),
            List.of(new CityScenario.Actor(0, "store", point)),
            List.of(point));
    try (SimulatorHttp http = new SimulatorHttp(config, stats, clock)) {
      Thread actor =
          Thread.ofVirtual()
              .start(
                  new CourierActor(scenario, descriptor, http, stats, clock, running::get, 2026));
      try {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while ((positions.get() == 0 || routes.get() == 0) && System.nanoTime() < deadline) {
          Thread.sleep(20);
        }
        assertTrue(
            positions.get() > 0,
            "A consulta de rota não pode impedir o primeiro envio de posição.");
        assertTrue(routes.get() > 0);
      } finally {
        running.set(false);
        actor.join(Duration.ofSeconds(2));
        assertFalse(actor.isAlive());
      }
    } finally {
      server.stop(0);
    }
  }
}
