package dev.bora.couriers.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.routing.RoutingService;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Execução explícita: não participa dos padrões de teste de unidade ou integração do CI. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(CourierBenchmarkDiagnostics.class)
class CourierPositionsBenchmark {
  private static final int COURIERS = 500;
  private static final int REQUESTS_PER_SECOND = 250;
  private static final int WARMUP_SECONDS = 60;
  private static final int MEASUREMENT_SECONDS = 60;
  private static final String MANIFEST = "routing/sp-fixture-manifest.json";
  private static final Path GRAPHS =
      Path.of("target", "routing-courier-benchmark").toAbsolutePath();

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres")) {
        @Override
        public String getHost() {
          if (Boolean.getBoolean("bora.benchmark.direct-docker-network")
              && getContainerId() != null) {
            return getContainerInfo()
                .getNetworkSettings()
                .getNetworks()
                .values()
                .iterator()
                .next()
                .getIpAddress();
          }
          return super.getHost();
        }

        @Override
        public Integer getMappedPort(int originalPort) {
          return Boolean.getBoolean("bora.benchmark.direct-docker-network")
              ? originalPort
              : super.getMappedPort(originalPort);
        }
      };

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    RoutingTools.prepareGraph(MANIFEST, GRAPHS);
    registry.add("bora.routing.graph-root", GRAPHS::toString);
    registry.add("bora.routing.manifest", () -> MANIFEST);
    registry.add("server.servlet.session.cookie.secure", () -> "false");
  }

  @Autowired JdbcClient jdbc;
  @Autowired SessionRepository<?> sessions;
  @Autowired CourierPositionBuffer buffer;
  @Autowired RoutingService routing;
  @Autowired JsonMapper json;

  @Autowired(required = false)
  CourierBenchmarkDiagnostics.JdbcMetrics diagnostics;

  @LocalServerPort int port;

  @Test
  @Timeout(240)
  void measuresAuthenticatedHttpPositionsAtFiveHundredCouriersWithoutRetries() throws Exception {
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      List<Account> accounts = prepareAccounts(http);
      assertThat(
              json.readTree(get(http, accounts.getFirst(), "/api/v1/auth/me").body())
                  .path("authenticated")
                  .asBoolean())
          .isTrue();
      if (diagnostics != null) diagnostics.reset();
      List<Result> warmup;
      try (Recording recording =
          diagnostics == null ? null : new Recording(Configuration.getConfiguration("profile"))) {
        if (recording != null) recording.start();
        warmup = measure(http, accounts, WARMUP_SECONDS);
        if (recording != null) {
          recording.stop();
          recording.dump(Path.of("target", "courier-positions-diagnostic.jfr"));
        }
      }
      Map<String, Object> warmupReport = new LinkedHashMap<>();
      warmupReport.put("requests", warmup.size());
      warmupReport.put(
          "statusCounts",
          warmup.stream()
              .collect(
                  java.util.stream.Collectors.groupingBy(
                      Result::status, java.util.stream.Collectors.counting())));
      warmupReport.put(
          "p95Millis",
          percentile(warmup.stream().map(Result::latencyMillis).sorted().toList(), .95));
      warmupReport.put(
          "errorExamples",
          warmup.stream()
              .filter(result -> result.status() != 202)
              .collect(java.util.stream.Collectors.groupingBy(Result::status))
              .entrySet()
              .stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      Map.Entry::getKey, entry -> entry.getValue().stream().limit(3).toList())));
      warmupReport.put("sentRequests", warmup.stream().filter(Result::sent).count());
      warmupReport.put("preSendDrops", warmup.stream().filter(result -> !result.sent()).count());
      warmupReport.put("maxInFlight", warmup.stream().mapToInt(Result::inFlight).max().orElse(0));
      warmupReport.put(
          "p95SchedulerLagMillis",
          percentile(
              warmup.stream()
                  .filter(Result::sent)
                  .map(Result::schedulerLagMillis)
                  .sorted()
                  .toList(),
              .95));
      warmupReport.put(
          "p95WireMillis",
          percentile(
              warmup.stream().filter(Result::sent).map(Result::wireMillis).sorted().toList(), .95));
      warmupReport.put(
          "pickupSnapMeters", routing.snap(CourierHttpFixture.PICKUP).distanceMeters());
      warmupReport.put("buffer", buffer.metrics());
      if (diagnostics != null) warmupReport.put("jdbcDiagnostics", diagnostics.snapshot());
      Files.writeString(
          Path.of("target", "courier-positions-warmup.json"),
          json.writerWithDefaultPrettyPrinter().writeValueAsString(warmupReport) + "\n");
      System.out.println("COURIER_POSITIONS_WARMUP " + json.writeValueAsString(warmupReport));
      Instant started = Instant.now();
      long before = System.nanoTime();
      List<Result> measured = measure(http, accounts, MEASUREMENT_SECONDS);
      long elapsedNanos = System.nanoTime() - before;
      long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (buffer.metrics().bufferedPoints() != 0 && System.nanoTime() < drainDeadline) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
      }
      List<Double> latencies = measured.stream().map(Result::latencyMillis).sorted().toList();
      List<Result> failures = measured.stream().filter(result -> result.status() != 202).toList();
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("startedAt", started.toString());
      report.put("finishedAt", Instant.now().toString());
      report.put("javaVersion", Runtime.version().toString());
      report.put("architecture", System.getProperty("os.arch"));
      report.put(
          "sourceSha256", System.getProperty("bora.benchmark.source-sha256", "not-recorded"));
      report.put(
          "databaseConnectionPath",
          Boolean.getBoolean("bora.benchmark.direct-docker-network")
              ? "Docker bridge IP e porta 5432; controle Testcontainers separado."
              : "Porta publicada pelo Docker no host.");
      report.put("routingManifest", MANIFEST);
      try (var input = getClass().getClassLoader().getResourceAsStream(MANIFEST)) {
        report.put("datasetSha256", json.readTree(input).path("sha256").asString());
      }
      report.put(
          "positionPattern",
          "500 entregadores no recorte da Paulista, uma posição próxima à loja por requisição.");
      report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
      report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
      report.put("couriers", COURIERS);
      report.put("requestsPerSecond", REQUESTS_PER_SECOND);
      report.put("warmupSeconds", WARMUP_SECONDS);
      report.put("measurementSeconds", MEASUREMENT_SECONDS);
      report.put("requests", measured.size());
      report.put("sentRequests", measured.stream().filter(Result::sent).count());
      report.put("preSendDrops", measured.stream().filter(result -> !result.sent()).count());
      report.put(
          "statusCounts",
          measured.stream()
              .collect(
                  java.util.stream.Collectors.groupingBy(
                      Result::status, java.util.stream.Collectors.counting())));
      report.put("successes", measured.size() - failures.size());
      report.put("failures", failures.size());
      report.put("errorExamples", failures.stream().limit(5).toList());
      List<Map<String, Object>> windows = new ArrayList<>();
      for (int window = 0; window < MEASUREMENT_SECONDS / 10; window++) {
        int currentWindow = window;
        List<Result> segment =
            measured.stream()
                .filter(
                    result -> result.requestIndex() / (REQUESTS_PER_SECOND * 10) == currentWindow)
                .toList();
        List<Double> successfulLatencies =
            segment.stream()
                .filter(result -> result.status() == 202)
                .map(Result::latencyMillis)
                .sorted()
                .toList();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fromSecond", window * 10);
        values.put("offered", segment.size());
        values.put("sent", segment.stream().filter(Result::sent).count());
        values.put("preSendDrops", segment.stream().filter(result -> !result.sent()).count());
        values.put("successes", successfulLatencies.size());
        values.put(
            "p95Millis",
            percentile(segment.stream().map(Result::latencyMillis).sorted().toList(), .95));
        values.put(
            "p95SuccessfulMillis",
            successfulLatencies.isEmpty() ? null : percentile(successfulLatencies, .95));
        windows.add(values);
      }
      report.put("measurementWindows", windows);
      report.put(
          "completedRequestsPerSecond",
          measured.stream().filter(result -> result.status() != 0).count()
              / (elapsedNanos / 1_000_000_000.0));
      report.put("p50Millis", percentile(latencies, .5));
      report.put("p95Millis", percentile(latencies, .95));
      report.put("p99Millis", percentile(latencies, .99));
      report.put("maxMillis", latencies.getLast());
      report.put("bufferedPointsAfterDrain", buffer.metrics().bufferedPoints());
      report.put("flushFailures", buffer.metrics().flushFailures());
      report.put("maxPersistLagMillis", buffer.metrics().maxPersistLagMillis());
      report.put("pickupSnapMeters", routing.snap(CourierHttpFixture.PICKUP).distanceMeters());
      List<UUID> missing = new ArrayList<>();
      for (Account account : accounts) {
        Instant expected =
            measured.stream()
                .filter(
                    result -> result.courier().equals(account.courier()) && result.status() == 202)
                .map(Result::recordedAt)
                .max(Instant::compareTo)
                .orElse(null);
        if (expected == null) {
          missing.add(account.courier());
          continue;
        }
        Instant stored =
            jdbc.sql("SELECT location_updated_at FROM couriers WHERE id=:id")
                .param("id", account.courier())
                .query((row, index) -> row.getTimestamp(1).toInstant())
                .single();
        if (!stored.equals(expected)) missing.add(account.courier());
      }
      report.put("couriersWithExpectedPersistedPosition", COURIERS - missing.size());
      Path output = Path.of("target", "courier-positions-benchmark.json");
      Files.writeString(
          output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
      System.out.println("COURIER_POSITIONS_BENCHMARK " + json.writeValueAsString(report));
      assertThat(measured).hasSize(REQUESTS_PER_SECOND * MEASUREMENT_SECONDS);
      assertThat(failures.size()).as("requisições sem HTTP202").isZero();
      assertThat(missing).isEmpty();
      assertThat(buffer.metrics().bufferedPoints()).isZero();
      assertThat(buffer.metrics().flushFailures()).isZero();
      assertThat(buffer.metrics().maxPersistLagMillis()).isLessThanOrEqualTo(1_000);
      assertThat(percentile(latencies, .95)).isLessThan(50);
    }
  }

  private List<Account> prepareAccounts(HttpClient http) throws Exception {
    List<Account> accounts = new ArrayList<>();
    Instant created = Instant.now();
    for (int index = 0; index < COURIERS; index++) {
      UUID context = UUID.randomUUID();
      UUID courier = UUID.randomUUID();
      UUID user = UUID.randomUUID();
      jdbc.sql("INSERT INTO demo_contexts(id,created_at,expires_at) VALUES(:id,:created,:expires)")
          .param("id", context)
          .param("created", created.atOffset(ZoneOffset.UTC))
          .param("expires", created.plusSeconds(7200).atOffset(ZoneOffset.UTC))
          .update();
      jdbc.sql(
              """
          INSERT INTO couriers(id,name,status,location,location_updated_at)
          VALUES(:id,'Entregador de carga','AVAILABLE',
            ST_SetSRID(ST_MakePoint(-46.6559,-23.5614),4326)::geography,:created)
          """)
          .param("id", courier)
          .param("created", created.minusSeconds(1).atOffset(ZoneOffset.UTC))
          .update();
      jdbc.sql(
              """
          INSERT INTO users(id,context_id,display_name,role,courier_id,expires_at)
          VALUES(:id,:context,'Entregador de carga','COURIER',:courier,:expires)
          """)
          .param("id", user)
          .param("context", context)
          .param("courier", courier)
          .param("expires", created.plusSeconds(7200).atOffset(ZoneOffset.UTC))
          .update();
      String cookie = createSession(sessions, user, context, courier);
      accounts.add(new Account(courier, cookie, null));
    }
    List<Account> ready = new ArrayList<>();
    for (Account account : accounts) {
      var response = get(http, account, "/api/v1/auth/csrf");
      assertThat(response.statusCode()).isEqualTo(200);
      ready.add(
          new Account(
              account.courier(),
              account.cookie(),
              json.readTree(response.body()).path("token").asString()));
    }
    return ready;
  }

  private static <S extends Session> String createSession(
      SessionRepository<S> repository, UUID user, UUID context, UUID courier) {
    S session = repository.createSession();
    session.setMaxInactiveInterval(Duration.ofHours(2));
    var security = SecurityContextHolder.createEmptyContext();
    security.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new DemoPrincipal(user, context, DemoRole.COURIER, null, courier, true),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_COURIER"))));
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
    session.setAttribute("bora.demo.context", context);
    repository.save(session);
    return "BORA_SESSION="
        + Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
  }

  private List<Result> measure(HttpClient http, List<Account> accounts, int seconds)
      throws Exception {
    ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    Semaphore capacity = new Semaphore(32);
    AtomicInteger active = new AtomicInteger();
    long start = System.nanoTime();
    long interval = TimeUnit.SECONDS.toNanos(1) / REQUESTS_PER_SECOND;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<java.util.concurrent.Future<?>> requests = new ArrayList<>();
      for (int index = 0; index < REQUESTS_PER_SECOND * seconds; index++) {
        int requestIndex = index;
        long scheduled = start + index * interval;
        long remaining;
        while ((remaining = scheduled - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
        Account account = accounts.get(index % accounts.size());
        Instant recorded = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!capacity.tryAcquire()) {
          results.add(
              new Result(
                  account.courier(),
                  recorded,
                  0,
                  (System.nanoTime() - scheduled) / 1_000_000.0,
                  "Gerador saturado.",
                  false,
                  0,
                  0,
                  active.get(),
                  requestIndex));
          continue;
        }
        int inFlight = active.incrementAndGet();
        requests.add(
            executor.submit(
                () -> {
                  int status = 0;
                  String error = null;
                  long sentAt = System.nanoTime();
                  try {
                    String body =
                        "{\"positions\":[{\"recordedAt\":\""
                            + recorded
                            + "\",\"position\":{\"latitude\":-23.56135,\"longitude\":-46.6558}}]}";
                    var response =
                        http.send(
                            HttpRequest.newBuilder(
                                    URI.create(
                                        "http://localhost:" + port + "/api/v1/courier/positions"))
                                .timeout(Duration.ofSeconds(5))
                                .header("Cookie", account.cookie())
                                .header("X-CSRF-TOKEN", account.csrf())
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                            HttpResponse.BodyHandlers.ofString());
                    status = response.statusCode();
                    if (status != 202) error = response.body();
                  } catch (Exception failure) {
                    error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                  } finally {
                    results.add(
                        new Result(
                            account.courier(),
                            recorded,
                            status,
                            (System.nanoTime() - scheduled) / 1_000_000.0,
                            error,
                            true,
                            (sentAt - scheduled) / 1_000_000.0,
                            (System.nanoTime() - sentAt) / 1_000_000.0,
                            inFlight,
                            requestIndex));
                    active.decrementAndGet();
                    capacity.release();
                  }
                }));
      }
      for (var request : requests) request.get(10, TimeUnit.SECONDS);
    }
    return List.copyOf(results);
  }

  private HttpResponse<String> get(HttpClient http, Account account, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Cookie", account.cookie())
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static double percentile(List<Double> sorted, double percentile) {
    return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1));
  }

  private record Account(UUID courier, String cookie, String csrf) {}

  private record Result(
      UUID courier,
      Instant recordedAt,
      int status,
      double latencyMillis,
      String error,
      boolean sent,
      double schedulerLagMillis,
      double wireMillis,
      int inFlight,
      int requestIndex) {}
}
