package dev.bora.couriers.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import dev.bora.couriers.PositionReceipt;
import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.routing.RoutingTools;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Qualificação explícita; não faz parte dos padrões *Test e *IT do CI. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatlingLoadBenchmark {
  private static final String MANIFEST = "routing/sp-fixture-manifest.json";
  private static final Path GRAPHS = Path.of("target", "routing-load-benchmark").toAbsolutePath();
  private static final int COURIERS = Integer.getInteger("bora.load.couriers", 500);
  private static final int ORDERS_PER_SECOND = COURIERS == 150 ? 1 : 2;
  private static final String INVARIANTS =
      """
      SELECT
        (SELECT count(*) FROM (SELECT order_id FROM offers WHERE status='PENDING'
          GROUP BY order_id HAVING count(*)>1) x) multiple_pending_per_order,
        (SELECT count(*) FROM (SELECT courier_id FROM offers WHERE status='PENDING'
          GROUP BY courier_id HAVING count(*)>1) x) multiple_pending_per_courier,
        (SELECT count(*) FROM (SELECT courier_id FROM orders
          WHERE status IN ('ASSIGNED','AT_STORE','PICKED_UP')
          GROUP BY courier_id HAVING count(*)>1) x) multiple_active_orders,
        (SELECT count(*) FROM couriers c WHERE c.status='OFFERED'
          AND NOT EXISTS (SELECT 1 FROM offers f WHERE f.courier_id=c.id
            AND f.status='PENDING')) offered_without_pending,
        (SELECT count(*) FROM offers f JOIN couriers c ON c.id=f.courier_id
          JOIN orders o ON o.id=f.order_id WHERE f.status='PENDING'
          AND (c.status<>'OFFERED' OR o.status<>'SEARCHING')) invalid_pending_state,
        (SELECT count(*) FROM orders o JOIN couriers c ON c.id=o.courier_id
          WHERE o.status IN ('ASSIGNED','AT_STORE','PICKED_UP')
          AND c.status<>'BUSY') invalid_assigned_state,
        (SELECT count(*) FROM orders o JOIN couriers c ON c.id=o.courier_id
          WHERE o.visitor_context_id IS NOT NULL AND c.simulated) private_to_simulator,
        (SELECT count(*) FROM offers WHERE expires_at<=created_at
          OR (status IN ('ACCEPTED','REJECTED') AND responded_at>=expires_at)
          OR (status='EXPIRED' AND responded_at<expires_at)) invalid_deadlines
      """;

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres")) {
        @Override
        public String getHost() {
          return Boolean.getBoolean("bora.benchmark.direct-docker-network")
                  && getContainerId() != null
              ? getContainerInfo()
                  .getNetworkSettings()
                  .getNetworks()
                  .values()
                  .iterator()
                  .next()
                  .getIpAddress()
              : super.getHost();
        }

        @Override
        public Integer getMappedPort(int port) {
          return Boolean.getBoolean("bora.benchmark.direct-docker-network")
              ? port
              : super.getMappedPort(port);
        }
      }.withCreateContainerCmdModifier(
          command -> {
            command.getHostConfig().withMemory(512L * 1024 * 1024).withNanoCPUs(1_000_000_000L);
            String cpuset = System.getenv("BORA_BENCHMARK_CPUSET");
            if (cpuset != null && !cpuset.isBlank()) command.getHostConfig().withCpusetCpus(cpuset);
          });

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    RoutingTools.prepareGraph(MANIFEST, GRAPHS);
    registry.add("bora.routing.graph-root", GRAPHS::toString);
    registry.add("bora.routing.manifest", () -> MANIFEST);
    registry.add("server.servlet.session.cookie.secure", () -> "false");
    registry.add("bora.dispatch.require-activation", () -> "false");
  }

  @Autowired JdbcClient jdbc;
  @Autowired SessionRepository<?> sessions;
  @MockitoSpyBean CourierPositionBuffer buffer;
  @Autowired JsonMapper json;
  @LocalServerPort int port;
  private final Map<UUID, AcceptedPosition> latestAccepted = new ConcurrentHashMap<>();
  private final AtomicLong acceptedReceipts = new AtomicLong();
  private final AtomicLong ignoredOnlyReceipts = new AtomicLong();

  @Test
  @Timeout(300)
  void qualifiesMixedAuthenticatedGpsAndOrderTraffic() throws Exception {
    assertThat(COURIERS).isIn(150, 500);
    Path fixture = Path.of("target", "gatling-fixture-" + COURIERS).toAbsolutePath();
    Path output = Path.of("target", "gatling-load-" + COURIERS + ".json");
    Path project =
        Path.of("..", "..", "infra", "benchmark", "gatling").toAbsolutePath().normalize();
    Files.createDirectories(fixture);
    Files.deleteIfExists(fixture.resolve("measured-orders.txt"));
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("qualified", false);
    report.put("startedAt", Instant.now().toString());
    report.put("sourceSha256", System.getProperty("bora.benchmark.source-sha256", "not-recorded"));
    report.put("gatlingVersion", "3.16.0");
    report.put("javaVersion", Runtime.version().toString());
    report.put("architecture", System.getProperty("os.arch"));
    report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
    report.put("databaseMemoryBytes", 512L * 1024 * 1024);
    report.put("databaseCpuQuota", 1);
    report.put("routingManifest", MANIFEST);
    try (var input = getClass().getClassLoader().getResourceAsStream(MANIFEST)) {
      report.put("datasetSha256", json.readTree(input).path("sha256").asString());
    }
    report.put("couriers", COURIERS);
    report.put("positionsPerSecond", COURIERS / 2);
    report.put("ordersPerSecond", ORDERS_PER_SECOND);
    report.put("warmupSeconds", 65);
    report.put("warmupRampSeconds", 20);
    report.put(
        "warmupMethod",
        "GPS de zero à taxa alvo em 20 s, seguido de 45 s na taxa alvo; pedidos a partir de 5 s. Mesma sequência de atores até o fim da medição.");
    report.put("measurementSeconds", 60);
    report.put(
        "positionPattern",
        "Uma posição por entregador a cada 2 s, na Paulista; 20 lojas públicas co-localizadas. Sem aceitar ofertas ou concluir entregas.");
    report.put(
        "authentication",
        "Sessões Spring Session JDBC preparadas no banco descartável; CSRF obtido por HTTP; filtros, persistência e Bucket4j de produção sem bypass.");
    report.put(
        "positionOracle",
        "Spy de teste chama enqueue real e registra epoch/timestamp aceitos em memória, sem SQL por GPS. O overhead do Mockito faz parte da medição. Mesmo epoch exige posição exata; epoch posterior permite descarte previsto pelo contrato de reserva/liberação.");
    Map<String, Long> maxima = new LinkedHashMap<>();
    int observations = 0;
    Process process = null;
    try (HttpClient http = HttpClient.newHttpClient()) {
      doAnswer(
              call -> {
                PositionReceipt receipt = (PositionReceipt) call.callRealMethod();
                if (receipt.accepted() > 0) {
                  acceptedReceipts.incrementAndGet();
                  UUID courier = call.getArgument(0);
                  long epoch = call.getArgument(3);
                  var candidate =
                      new AcceptedPosition(epoch, receipt.latestRecordedAt(), Instant.now());
                  latestAccepted.compute(
                      courier,
                      (id, previous) ->
                          previous == null
                                  || candidate.epoch() > previous.epoch()
                                  || (candidate.epoch() == previous.epoch()
                                      && candidate.recordedAt().isAfter(previous.recordedAt()))
                              ? candidate
                              : previous);
                } else {
                  ignoredOnlyReceipts.incrementAndGet();
                }
                return receipt;
              })
          .when(buffer)
          .enqueue(any(), anyList(), any(), anyLong());
      prepareActors(http, fixture);
      List<String> command =
          new ArrayList<>(
              List.of(
                  Path.of("..", "..", "mvnw").toAbsolutePath().normalize().toString(),
                  "--batch-mode",
                  "--no-transfer-progress",
                  "-f",
                  project.resolve("pom.xml").toString(),
                  "-Dmaven.repo.local=" + System.getProperty("maven.repo.local"),
                  "-Dgatling.simulationClass=dev.bora.load.DispatchSimulation",
                  "-Dbora.load.fixture=" + fixture,
                  "-Dbora.load.base-url=http://127.0.0.1:" + port,
                  "-Dbora.load.couriers=" + COURIERS,
                  "-Dbora.load.orders-per-second=" + ORDERS_PER_SECOND,
                  "gatling:test"));
      var builder =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(fixture.resolve("gatling.log").toFile());
      builder.environment().put("MAVEN_OPTS", "-Xmx256m -XX:ActiveProcessorCount=2");
      Instant processStarted = Instant.now();
      process = builder.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(220);
      long lastObservation = System.nanoTime();
      long maxObserverGapMillis = 0;
      while (process.isAlive() && System.nanoTime() < deadline) {
        long now = System.nanoTime();
        maxObserverGapMillis =
            Math.max(maxObserverGapMillis, TimeUnit.NANOSECONDS.toMillis(now - lastObservation));
        lastObservation = now;
        Map<String, Long> current =
            jdbc.sql(INVARIANTS)
                .query(
                    (row, index) -> {
                      Map<String, Long> counts = new LinkedHashMap<>();
                      for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) {
                        counts.put(row.getMetaData().getColumnLabel(column), row.getLong(column));
                      }
                      return counts;
                    })
                .single();
        current.forEach((key, value) -> maxima.merge(key, value, Math::max));
        observations++;
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500));
      }
      report.put("observerMaxGapMillis", maxObserverGapMillis);
      report.put("invariantObservations", observations);
      report.put("invariantMaxima", maxima);
      if (process.isAlive()) {
        stopGenerator(process);
        throw new IllegalStateException("O executor Gatling excedeu 220 segundos.");
      }
      report.put("gatlingExitCode", process.exitValue());
      long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (buffer.metrics().bufferedPoints() != 0 && System.nanoTime() < drainDeadline) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
      }
      report.put("buffer", buffer.metrics());
      List<UUID> measured =
          Files.exists(fixture.resolve("measured-orders.txt"))
              ? Files.readAllLines(fixture.resolve("measured-orders.txt")).stream()
                  .map(UUID::fromString)
                  .toList()
              : List.of();
      report.put("measuredOrders", measured.size());
      List<Double> offers =
          measured.isEmpty()
              ? List.of()
              : jdbc.sql(
                      """
          SELECT extract(epoch FROM (min(f.created_at)-o.created_at))*1000 latency
          FROM orders o LEFT JOIN offers f ON f.order_id=o.id WHERE o.id IN (:ids)
          GROUP BY o.id,o.created_at HAVING min(f.created_at) IS NOT NULL ORDER BY latency
          """)
                  .param("ids", measured)
                  .query(Double.class)
                  .list();
      report.put("ordersWithFirstOffer", offers.size());
      report.put(
          "firstOfferP95Millis",
          offers.isEmpty() ? null : offers.get((int) Math.ceil(offers.size() * .95) - 1));
      report.put(
          "persistedFreshCouriers",
          jdbc.sql(
                  """
          SELECT count(*) FROM couriers WHERE simulated
          AND location_updated_at>=:started
          """)
              .param("started", processStarted.atOffset(ZoneOffset.UTC))
              .query(Long.class)
              .single());
      List<String> expectedPositions =
          Files.readAllLines(fixture.resolve("expected-positions.tsv"));
      Map<UUID, Instant> expectedByCourier = new LinkedHashMap<>();
      for (String line : expectedPositions) {
        String[] fields = line.split("\t");
        expectedByCourier.put(UUID.fromString(fields[0]), Instant.parse(fields[1]));
      }
      List<Map<String, Object>> finalPositions =
          jdbc.sql(
                  """
                  SELECT c.id,c.status,c.position_epoch,c.location_updated_at,
                    f.created_at offer_created_at,f.expires_at offer_expires_at,
                    f.responded_at offer_responded_at,f.status offer_status
                  FROM couriers c LEFT JOIN LATERAL (
                    SELECT created_at,expires_at,responded_at,status FROM offers
                    WHERE courier_id=c.id ORDER BY created_at DESC,id DESC LIMIT 1
                  ) f ON true WHERE c.id IN (:ids) ORDER BY c.id
                  """)
              .param("ids", expectedByCourier.keySet())
              .query(
                  (row, index) -> {
                    Map<String, Object> position = new LinkedHashMap<>();
                    position.put("courierId", row.getObject("id", UUID.class));
                    position.put("status", row.getString("status"));
                    position.put("currentEpoch", row.getLong("position_epoch"));
                    position.put(
                        "persistedRecordedAt",
                        row.getObject("location_updated_at", OffsetDateTime.class).toInstant());
                    position.put("lastOfferStatus", row.getString("offer_status"));
                    for (String field :
                        List.of("offer_created_at", "offer_expires_at", "offer_responded_at")) {
                      OffsetDateTime instant = row.getObject(field, OffsetDateTime.class);
                      position.put(field, instant == null ? null : instant.toInstant());
                    }
                    return position;
                  })
              .list();
      int rawExactPositions = 0;
      int sameEpochExactPositions = 0;
      int newerEpochPositions = 0;
      int unexplainedPositions = 0;
      List<Map<String, Object>> epochEvidence = new ArrayList<>();
      for (Map<String, Object> position : finalPositions) {
        UUID courier = (UUID) position.get("courierId");
        Instant actual = (Instant) position.get("persistedRecordedAt");
        Instant lastResponse = expectedByCourier.get(courier);
        AcceptedPosition accepted = latestAccepted.get(courier);
        long currentEpoch = (Long) position.get("currentEpoch");
        if (actual.equals(lastResponse)) rawExactPositions++;
        boolean sameEpochExact =
            accepted != null
                && currentEpoch == accepted.epoch()
                && actual.equals(accepted.recordedAt());
        boolean newerEpoch = accepted != null && currentEpoch > accepted.epoch();
        if (sameEpochExact) sameEpochExactPositions++;
        else if (newerEpoch) newerEpochPositions++;
        else unexplainedPositions++;
        if (!sameEpochExact) {
          position.put("latestResponseRecordedAt", lastResponse);
          position.put("latestAccepted", accepted);
          position.put("reason", newerEpoch ? "NEWER_EPOCH" : "UNEXPLAINED");
          epochEvidence.add(position);
        }
      }
      report.put("capturedAcceptedCouriers", latestAccepted.size());
      report.put("plannedPositionReceipts", (COURIERS / 2) * 115L);
      report.put("acceptedPositionReceipts", acceptedReceipts.get());
      report.put("ignoredOnlyPositionReceipts", ignoredOnlyReceipts.get());
      report.put("checkedPersistedCouriers", finalPositions.size());
      report.put("couriersWithExactLatestPosition", rawExactPositions);
      report.put("couriersWithSameEpochExactPosition", sameEpochExactPositions);
      report.put("couriersWithNewerEpoch", newerEpochPositions);
      report.put("couriersWithUnexplainedPositionMismatch", unexplainedPositions);
      report.put("positionEpochEvidence", epochEvidence);
      report.put("gatlingStatistics", parseStatistics(fixture.resolve("request-samples.tsv")));
      assertThat(process.exitValue()).as("Gatling; relatório nativo preservado").isZero();
      assertThat(maxObserverGapMillis).as("Suspensão ou observador interrompido").isLessThan(5_000);
      assertThat(observations).isGreaterThan(200);
      assertThat(maxima.values()).allMatch(value -> value == 0);
      assertThat(measured).hasSize(ORDERS_PER_SECOND * 60).doesNotHaveDuplicates();
      assertThat(offers).hasSize(measured.size());
      if (COURIERS == 150)
        assertThat(offers.get((int) Math.ceil(offers.size() * .95) - 1)).isLessThan(2_000);
      assertThat((Long) report.get("persistedFreshCouriers")).isEqualTo(COURIERS);
      assertThat(expectedByCourier).hasSize(COURIERS);
      assertThat(latestAccepted).hasSize(COURIERS);
      assertThat(acceptedReceipts.get()).isEqualTo((COURIERS / 2) * 115L);
      assertThat(ignoredOnlyReceipts.get()).isZero();
      assertThat(finalPositions).hasSize(COURIERS);
      assertThat(unexplainedPositions).isZero();
      assertThat(sameEpochExactPositions + newerEpochPositions).isEqualTo(COURIERS);
      assertThat(buffer.metrics().bufferedPoints()).isZero();
      assertThat(buffer.metrics().flushFailures()).isZero();
      assertThat(buffer.metrics().maxPersistLagMillis()).isLessThanOrEqualTo(1_000);
      report.put("qualified", true);
    } catch (Throwable failure) {
      report.put("failureType", failure.getClass().getName());
      report.put("failureMessage", String.valueOf(failure.getMessage()));
      throw failure;
    } finally {
      if (process != null) stopGenerator(process);
      report.put("finishedAt", Instant.now().toString());
      Files.writeString(
          output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
      Files.deleteIfExists(fixture.resolve("couriers.tsv"));
      Files.deleteIfExists(fixture.resolve("stores.tsv"));
      System.out.println("GATLING_LOAD_REPORT " + output.toAbsolutePath());
    }
  }

  private record AcceptedPosition(long epoch, Instant recordedAt, Instant acceptedAt) {}

  private static void stopGenerator(Process process) {
    var descendants = process.descendants().toList();
    for (var child : descendants.reversed()) child.destroyForcibly();
    process.destroyForcibly();
    try {
      process.waitFor(5, TimeUnit.SECONDS);
      for (var child : descendants) {
        if (child.isAlive()) child.onExit().get(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException failure) {
      for (var child : descendants) if (child.isAlive()) child.destroyForcibly();
    }
  }

  private Map<String, Object> parseStatistics(Path log) throws Exception {
    Map<String, List<Long>> latencies = new LinkedHashMap<>();
    Map<String, Long> failures = new LinkedHashMap<>();
    for (String line : Files.readAllLines(log)) {
      String[] fields = line.split("\t", -1);
      if (fields.length != 3) throw new IllegalStateException("Amostra Gatling incompleta.");
      String name = fields[0];
      long elapsed = Long.parseLong(fields[1]);
      latencies.computeIfAbsent(name, ignored -> new ArrayList<>()).add(elapsed);
      if (!fields[2].equals("OK")) failures.merge(name, 1L, Long::sum);
    }
    Map<String, Object> stats = new LinkedHashMap<>();
    latencies.forEach(
        (name, values) -> {
          values.sort(Long::compareTo);
          stats.put(
              name,
              Map.of(
                  "requests",
                  values.size(),
                  "failures",
                  failures.getOrDefault(name, 0L),
                  "p50Millis",
                  values.get((int) Math.ceil(values.size() * .50) - 1),
                  "p95Millis",
                  values.get((int) Math.ceil(values.size() * .95) - 1),
                  "p99Millis",
                  values.get((int) Math.ceil(values.size() * .99) - 1),
                  "maxMillis",
                  values.getLast()));
        });
    return stats;
  }

  private void prepareActors(HttpClient http, Path fixture) throws Exception {
    for (DemoRole role : List.of(DemoRole.COURIER, DemoRole.STORE)) {
      List<String> rows = new ArrayList<>();
      for (int index = 0; index < (role == DemoRole.COURIER ? COURIERS : 20); index++) {
        UUID context = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.sql(
                "INSERT INTO demo_contexts(id,expires_at) VALUES(:id,clock_timestamp()+interval '2 hours')")
            .param("id", context)
            .update();
        if (role == DemoRole.COURIER) {
          jdbc.sql(
                  """
              INSERT INTO couriers(id,name,status,location,location_updated_at,simulated)
              VALUES(:id,'Entregador Gatling','AVAILABLE',
                ST_SetSRID(ST_MakePoint(-46.6559,-23.5614),4326)::geography,
                clock_timestamp()-interval '1 second',true)
              """)
              .param("id", actor)
              .update();
        } else {
          jdbc.sql(
                  """
              INSERT INTO stores(id,name,service_area_id,location,simulated)
              SELECT :id,'Loja Gatling',service_area_id,location,true FROM stores
              WHERE id='20000000-0000-4000-8000-000000000001'
              """)
              .param("id", actor)
              .update();
        }
        jdbc.sql(
                """
            INSERT INTO users(id,context_id,display_name,role,store_id,courier_id,visitor,expires_at)
            VALUES(:id,:context,'Ator Gatling',:role,:store,:courier,false,
              clock_timestamp()+interval '2 hours')
            """)
            .param("id", user)
            .param("context", context)
            .param("role", role.name())
            .param("store", role == DemoRole.STORE ? actor : null)
            .param("courier", role == DemoRole.COURIER ? actor : null)
            .update();
        String cookie =
            createSession(
                sessions,
                new DemoPrincipal(
                    user,
                    context,
                    role,
                    role == DemoRole.STORE ? actor : null,
                    role == DemoRole.COURIER ? actor : null,
                    false));
        var response =
            http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf"))
                    .header("Cookie", cookie)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        rows.add(
            cookie + "\t" + json.readTree(response.body()).path("token").asString() + "\t" + actor);
      }
      Files.write(fixture.resolve(role == DemoRole.COURIER ? "couriers.tsv" : "stores.tsv"), rows);
    }
  }

  private static <S extends Session> String createSession(
      SessionRepository<S> repository, DemoPrincipal actor) {
    S session = repository.createSession();
    session.setMaxInactiveInterval(Duration.ofHours(2));
    var security = SecurityContextHolder.createEmptyContext();
    security.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            actor, null, List.of(new SimpleGrantedAuthority("ROLE_" + actor.role().name()))));
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
    session.setAttribute("bora.demo.context", actor.contextId());
    repository.save(session);
    return "BORA_SESSION="
        + Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
  }
}
