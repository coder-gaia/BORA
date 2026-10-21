package dev.bora.load;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Somente o host de teste cria esta fixture e escolhe a porta local aleatória. */
public final class DispatchSimulation extends Simulation {
  private static final int WARMUP = 65;
  private static final int MEASURE = 60;
  private final Path fixture = Path.of(required("bora.load.fixture"));
  private final int couriers = Integer.parseInt(required("bora.load.couriers"));
  private final int ordersPerSecond = Integer.parseInt(required("bora.load.orders-per-second"));
  private final int positionsPerSecond = couriers / 2;
  private final Iterator<Map<String, Object>> positionAccounts = accounts("couriers.tsv", couriers);
  private final ConcurrentLinkedQueue<String> samples = new ConcurrentLinkedQueue<>();
  private final ConcurrentHashMap<String, Instant> persisted = new ConcurrentHashMap<>();

  public DispatchSimulation() {
    String baseUrl = required("bora.load.base-url");
    if (!baseUrl.matches("http://127\\.0\\.0\\.1:[0-9]+")) {
      throw new IllegalArgumentException("A carga exige a API isolada em loopback.");
    }
    var protocol =
        http.baseUrl(baseUrl)
            .contentTypeHeader("application/json")
            .acceptHeader("application/json")
            .disableCaching()
            .disableFollowRedirect()
            .shareConnections()
            .disableWarmUp();
    setUp(
            scenario("Aquecimento GPS")
                .exec(position("GPS aquecimento"))
                .injectOpen(
                    rampUsersPerSec(0).to(positionsPerSecond).during(20),
                    constantUsersPerSec(positionsPerSecond).during(WARMUP - 20)),
            scenario("Aquecimento pedidos")
                .exec(order("Pedido aquecimento", false))
                .injectOpen(nothingFor(5), constantUsersPerSec(ordersPerSecond).during(WARMUP - 5)),
            scenario("Posições medidas")
                .exec(position("GPS medido"))
                .injectOpen(
                    nothingFor(WARMUP), constantUsersPerSec(positionsPerSecond).during(MEASURE)),
            scenario("Pedidos medidos")
                .exec(order("Pedido medido", true))
                .injectOpen(
                    nothingFor(WARMUP), constantUsersPerSec(ordersPerSecond).during(MEASURE)))
        .protocols(protocol)
        .maxDuration(Duration.ofSeconds(WARMUP + MEASURE + 20))
        .assertions(
            global().failedRequests().count().is(0L),
            details("GPS medido").allRequests().count().is((long) positionsPerSecond * MEASURE),
            details("GPS medido").responseTime().percentile(95).lt(50),
            details("Pedido medido").allRequests().count().is((long) ordersPerSecond * MEASURE));
  }

  @Override
  public void after() {
    try {
      Files.write(fixture.resolve("request-samples.tsv"), samples);
      Files.write(
          fixture.resolve("expected-positions.tsv"),
          persisted.entrySet().stream()
              .map(entry -> entry.getKey() + "\t" + entry.getValue())
              .toList());
    } catch (IOException failure) {
      throw new IllegalStateException("Não foi possível registrar os tempos HTTP.", failure);
    }
  }

  private ChainBuilder position(String name) {
    return feed(positionAccounts)
        .exec(
            session ->
                session.set("recordedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString()))
        .exec(
            http(name)
                .post("/api/v1/courier/positions")
                .header("Cookie", "#{cookie}")
                .header("X-CSRF-TOKEN", "#{csrf}")
                .body(
                    StringBody(
                        session ->
                            "{\"positions\":[{\"recordedAt\":\""
                                + session.getString("recordedAt")
                                + "\",\"position\":{\"latitude\":-23.56135,\"longitude\":-46.6558}}]}"))
                .check(
                    responseTimeInMillis().saveAs("latency"),
                    status().is(202),
                    jsonPath("$.accepted").ofInt().is(1)))
        .exec(
            session -> {
              if (!session.isFailed())
                persisted.merge(
                    session.getString("actor"),
                    Instant.parse(session.getString("recordedAt")),
                    (left, right) -> left.isAfter(right) ? left : right);
              return record(name, session);
            });
  }

  private ChainBuilder order(String name, boolean measured) {
    var request =
        feed(accounts("stores.tsv", 20))
            .exec(session -> session.set("key", "m13-" + UUID.randomUUID()))
            .exec(
                http(name)
                    .post("/api/v1/orders")
                    .header("Cookie", "#{cookie}")
                    .header("X-CSRF-TOKEN", "#{csrf}")
                    .header("Idempotency-Key", "#{key}")
                    .body(
                        StringBody("{\"dropoff\":{\"latitude\":-23.5650,\"longitude\":-46.6500}}"))
                    .check(
                        responseTimeInMillis().saveAs("latency"),
                        status().is(201),
                        jsonPath("$.id").saveAs("orderId")))
            .exec(session -> record(name, session));
    return measured
        ? request.exec(
            session -> {
              if (!session.isFailed() && session.contains("orderId")) {
                appendOrder(session.getString("orderId"));
              }
              return session;
            })
        : request;
  }

  private Session record(String name, Session session) {
    samples.add(
        name
            + "\t"
            + (session.contains("latency") ? session.getInt("latency") : -1)
            + "\t"
            + (session.isFailed() ? "KO" : "OK"));
    return session;
  }

  private Iterator<Map<String, Object>> accounts(String name, int expected) {
    try {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (String line : Files.readAllLines(fixture.resolve(name))) {
        String[] values = line.split("\\t", -1);
        rows.add(Map.of("cookie", values[0], "csrf", values[1], "actor", values[2]));
      }
      if (rows.size() != expected) throw new IllegalStateException("Fixture incompleta.");
      AtomicInteger next = new AtomicInteger();
      return new Iterator<>() {
        public boolean hasNext() {
          return true;
        }

        public Map<String, Object> next() {
          return rows.get(Math.floorMod(next.getAndIncrement(), rows.size()));
        }
      };
    } catch (IOException failure) {
      throw new IllegalStateException("Não foi possível abrir a fixture isolada.", failure);
    }
  }

  private synchronized void appendOrder(String id) {
    try {
      Files.writeString(
          fixture.resolve("measured-orders.txt"),
          id + "\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException failure) {
      throw new IllegalStateException("Não foi possível registrar o pedido medido.", failure);
    }
  }

  private static String required(String key) {
    String value = System.getProperty(key);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Configuração ausente: " + key);
    return value;
  }
}
