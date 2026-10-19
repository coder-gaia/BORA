package dev.bora.simulator;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SimulatorHttpTest {
  private static final String KEY = "test-only-simulator-key-0123456789abcdef";

  @Test
  void recordsControlledErrorCodesWithoutResponsePayload() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/simulator/scenario",
        exchange ->
            Fixture.json(
                exchange,
                503,
                "{\"code\":\"POSITION_STORAGE_DELAYED\",\"message\":\"private-payload\"}"));
    server.start();
    var stats = new SimulatorStats(Instant.now());
    var config =
        new SimulatorConfig(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            KEY,
            2026,
            Duration.ZERO,
            8090,
            120,
            40,
            false,
            null);
    try (var http = new SimulatorHttp(config, stats, new ServerClock(System::nanoTime))) {
      assertEquals(503, http.scenario().status());
      String report = SimulatorHttp.JSON.writeValueAsString(stats.snapshot(Instant.now()));
      assertTrue(report.contains("apiError503_POSITION_STORAGE_DELAYED"));
      assertFalse(report.contains("private-payload"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void secureSessionOnInternalHttpNeedsExplicitOptInAndDoesNotRelaxOtherCookies() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.secureCookies = true;
      try (SimulatorHttp normal = fixture.client()) {
        assertThrows(
            SimulatorHttp.ApiFailure.class,
            () -> normal.actor("COURIER", 1).get("/api/v1/courier/work"));
      }
      fixture.seen.clear();
      try (SimulatorHttp internal = fixture.client(true)) {
        assertEquals(200, internal.actor("COURIER", 1).get("/api/v1/courier/work").status());
      }
      Seen work =
          fixture.seen.stream()
              .filter(seen -> seen.path.equals("/api/v1/courier/work"))
              .findFirst()
              .orElseThrow();
      assertTrue(work.cookie.contains("BORA_SESSION"));
      assertFalse(work.cookie.contains("OTHER_PRIVATE"));
      assertTrue(
          fixture.seen.stream()
              .filter(seen -> seen.path.equals("/api/v1/auth/simulator"))
              .allMatch(seen -> seen.csrf != null));
    }
  }

  @Test
  void internalHttpOptInKeepsNormalCookiePathFiltering() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.secureCookies = true;
      fixture.cookiePath = "/api/v1/auth";
      try (SimulatorHttp internal = fixture.client(true)) {
        assertEquals(200, internal.actor("COURIER", 1).get("/api/v1/courier/work").status());
      }
      Seen work =
          fixture.seen.stream()
              .filter(seen -> seen.path.equals("/api/v1/courier/work"))
              .findFirst()
              .orElseThrow();
      assertFalse(work.cookie.contains("BORA_SESSION"));
    }
  }

  @Test
  void keepsActorCookiesSeparateAndSendsPrivilegedKeyOnlyToBootstrapLoginAndIdleRoute()
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      try (SimulatorHttp http = fixture.client()) {
        assertEquals(200, http.scenario().status());
        var first = http.actor("COURIER", 1);
        var second = http.actor("STORE", 2);
        assertEquals(200, first.get("/api/v1/courier/work").status());
        assertEquals(200, second.get("/api/v1/orders").status());
        assertEquals(200, first.get("/api/v1/courier/work").status());
        assertEquals(200, first.post("/api/v1/simulator/route", java.util.Map.of()).status());
      }
      List<Seen> work =
          fixture.seen.stream().filter(seen -> seen.path.equals("/api/v1/courier/work")).toList();
      assertEquals(2, work.size());
      assertTrue(work.stream().allMatch(seen -> seen.cookie.contains("COURIER-1")));
      Seen orders =
          fixture.seen.stream()
              .filter(seen -> seen.path.equals("/api/v1/orders"))
              .findFirst()
              .orElseThrow();
      assertTrue(orders.cookie.contains("STORE-2"));
      assertTrue(
          fixture.seen.stream()
              .filter(seen -> seen.path.equals("/api/v1/auth/simulator"))
              .allMatch(seen -> KEY.equals(seen.key) && seen.csrf != null));
      assertTrue(
          fixture.seen.stream()
              .filter(seen -> !seen.path.contains("simulator"))
              .allMatch(seen -> seen.key == null));
      assertEquals(
          1,
          fixture.seen.stream()
              .filter(
                  seen ->
                      seen.path.equals("/api/v1/simulator/route")
                          && KEY.equals(seen.key)
                          && seen.csrf != null)
              .count());
    }
  }

  @Test
  void renewsExpiredIdentityThroughDedicatedLoginAndRetriesOnlyUnauthorizedRequest()
      throws Exception {
    try (Fixture fixture = new Fixture();
        SimulatorHttp http = fixture.client()) {
      var actor = http.actor("COURIER", 7);
      fixture.rejectSessionOnce.set(true);
      assertEquals(200, actor.get("/api/v1/courier/work").status());
      assertEquals(2, fixture.logins.get());
      assertEquals(
          2,
          fixture.seen.stream().filter(seen -> seen.path.equals("/api/v1/courier/work")).count());
      assertTrue(fixture.seen.stream().noneMatch(seen -> seen.path.equals("/api/v1/auth/demo")));
    }
  }

  @Test
  void refreshesCsrfAfterRotationAndExecutesMutationOnce() throws Exception {
    try (Fixture fixture = new Fixture();
        SimulatorHttp http = fixture.client()) {
      var actor = http.actor("COURIER", 8);
      fixture.rejectCsrfOnce.set(true);
      assertEquals(204, actor.post("/api/v1/offers/test/accept", null).status());
      assertEquals(1, fixture.executedMutations.get());
      assertEquals(1, fixture.logins.get());
      assertEquals(
          2,
          fixture.seen.stream()
              .filter(seen -> seen.path.equals("/api/v1/offers/test/accept"))
              .count());
    }
  }

  @Test
  void leavesTransportAmbiguousMutationForStateReconciliationInsteadOfBlindRetry()
      throws Exception {
    try (Fixture fixture = new Fixture();
        SimulatorHttp http = fixture.client()) {
      var actor = http.actor("COURIER", 9);
      fixture.breakMutationResponse.set(true);
      assertThrows(IOException.class, () -> actor.post("/api/v1/offers/test/accept", null));
      assertEquals(1, fixture.executedMutations.get());
      assertEquals(
          1,
          fixture.seen.stream()
              .filter(seen -> seen.path.equals("/api/v1/offers/test/accept"))
              .count());
    }
  }

  @Test
  void preservesRetryAfterAndStableIdempotencyKeyForCallerToRetryCreation() throws Exception {
    try (Fixture fixture = new Fixture();
        SimulatorHttp http = fixture.client()) {
      var actor = http.actor("STORE", 0);
      fixture.rateLimitOnce.set(true);
      var first =
          actor.createOrder(java.util.Map.of("dropoff", new GeoPoint(-23.56, -46.66)), "same-key");
      assertEquals(429, first.status());
      assertEquals(Duration.ofSeconds(7), first.retryAfter());
      assertEquals(
          200,
          actor
              .createOrder(java.util.Map.of("dropoff", new GeoPoint(-23.56, -46.66)), "same-key")
              .status());
      assertEquals(
          List.of("same-key", "same-key"),
          fixture.seen.stream()
              .filter(seen -> seen.path.equals("/api/v1/orders"))
              .map(seen -> seen.idempotency)
              .toList());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final HttpServer server;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final AtomicInteger logins = new AtomicInteger();
    final AtomicInteger sessions = new AtomicInteger();
    final AtomicInteger executedMutations = new AtomicInteger();
    final AtomicBoolean rejectSessionOnce = new AtomicBoolean();
    final AtomicBoolean rejectCsrfOnce = new AtomicBoolean();
    final AtomicBoolean breakMutationResponse = new AtomicBoolean();
    final AtomicBoolean rateLimitOnce = new AtomicBoolean();
    boolean secureCookies;
    String cookiePath = "/";

    Fixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.start();
    }

    SimulatorHttp client() {
      return client(false);
    }

    SimulatorHttp client(boolean internalHttp) {
      SimulatorConfig config =
          new SimulatorConfig(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              KEY,
              2026,
              Duration.ZERO,
              8090,
              120,
              40,
              internalHttp,
              null);
      return new SimulatorHttp(
          config, new SimulatorStats(Instant.now()), new ServerClock(System::nanoTime));
    }

    void handle(HttpExchange exchange) throws IOException {
      String path = exchange.getRequestURI().getPath();
      String cookie = exchange.getRequestHeaders().getFirst("Cookie");
      String key = exchange.getRequestHeaders().getFirst("X-Bora-Simulator-Key");
      String csrf = exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN");
      String idempotency = exchange.getRequestHeaders().getFirst("Idempotency-Key");
      seen.add(new Seen(path, cookie == null ? "" : cookie, key, csrf, idempotency));
      if (path.equals("/api/v1/auth/csrf")) {
        if (cookie == null) {
          exchange
              .getResponseHeaders()
              .add(
                  "Set-Cookie",
                  "BORA_SESSION=initial-"
                      + sessions.incrementAndGet()
                      + "; Path="
                      + cookiePath
                      + "; HttpOnly"
                      + (secureCookies ? "; Secure" : ""));
          if (secureCookies) {
            exchange
                .getResponseHeaders()
                .add("Set-Cookie", "OTHER_PRIVATE=test; Path=/; Secure; HttpOnly");
          }
        }
        json(exchange, 200, "{\"headerName\":\"X-CSRF-TOKEN\",\"token\":\"csrf-token\"}");
      } else if (path.equals("/api/v1/auth/simulator")) {
        var body = SimulatorHttp.JSON.readTree(exchange.getRequestBody());
        if (!KEY.equals(key) || csrf == null || cookie == null) {
          json(exchange, 403, "{\"code\":\"FORBIDDEN\"}");
          return;
        }
        logins.incrementAndGet();
        exchange
            .getResponseHeaders()
            .add(
                "Set-Cookie",
                "BORA_SESSION="
                    + body.path("role").asString()
                    + "-"
                    + body.path("index").asInt()
                    + "; Path="
                    + cookiePath
                    + "; HttpOnly"
                    + (secureCookies ? "; Secure" : ""));
        json(exchange, 200, "{\"authenticated\":true}");
      } else if (path.equals("/api/v1/simulator/scenario")) {
        json(exchange, KEY.equals(key) ? 200 : 403, "{\"serverTime\":\"2026-10-19T12:00:00Z\"}");
      } else if (path.equals("/api/v1/courier/work")
          && rejectSessionOnce.compareAndSet(true, false)) {
        json(exchange, 401, "{\"code\":\"UNAUTHENTICATED\"}");
      } else if (path.equals("/api/v1/orders") && rateLimitOnce.compareAndSet(true, false)) {
        exchange.getResponseHeaders().add("Retry-After", "7");
        json(exchange, 429, "{\"code\":\"RATE_LIMIT_EXCEEDED\"}");
      } else if (path.equals("/api/v1/offers/test/accept")) {
        if (rejectCsrfOnce.compareAndSet(true, false)) {
          json(exchange, 403, "{\"code\":\"INVALID_CSRF\"}");
          return;
        }
        executedMutations.incrementAndGet();
        if (breakMutationResponse.get()) {
          exchange.sendResponseHeaders(200, 100);
          exchange.getResponseBody().write(1);
          exchange.close();
        } else {
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        }
      } else {
        json(exchange, 200, "{}");
      }
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private record Seen(String path, String cookie, String key, String csrf, String idempotency) {}
}
