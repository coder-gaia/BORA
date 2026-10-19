package dev.bora.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bora.BoraApplication;
import dev.bora.realtime.internal.PostgresChangeBridge;
import dev.bora.routing.RoutingTools;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealtimeClusterIT {
  static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static final PostgreSQLContainer DATABASE =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  ConfigurableApplicationContext first;
  ConfigurableApplicationContext second;
  int firstPort;
  int secondPort;
  JdbcClient jdbc;

  @BeforeAll
  void start() {
    String manifest = "routing/sp-fixture-manifest.json";
    Path graphs = Path.of("target", "routing-realtime-it").toAbsolutePath();
    RoutingTools.prepareGraph(manifest, graphs);
    String[] arguments = {
      "--server.port=0",
      "--spring.datasource.url=" + DATABASE.getJdbcUrl(),
      "--spring.datasource.username=" + DATABASE.getUsername(),
      "--spring.datasource.password=" + DATABASE.getPassword(),
      "--spring.session.jdbc.initialize-schema=never",
      "--spring.session.timeout=2h",
      "--server.servlet.session.cookie.secure=false",
      "--bora.routing.graph-root=" + graphs,
      "--bora.routing.manifest=" + manifest,
      "--bora.dispatch.worker-enabled=false",
      "--bora.dispatch.expiry-enabled=false",
      "--bora.fulfillment.reconciliation-enabled=false"
    };
    first = new SpringApplicationBuilder(BoraApplication.class).run(arguments);
    second = new SpringApplicationBuilder(BoraApplication.class).run(arguments);
    firstPort = ((WebServerApplicationContext) first).getWebServer().getPort();
    secondPort = ((WebServerApplicationContext) second).getWebServer().getPort();
    jdbc = first.getBean(JdbcClient.class);
  }

  @AfterAll
  void stop() {
    if (second != null) {
      second.close();
    }
    if (first != null) {
      first.close();
    }
  }

  @BeforeEach
  void resetLimits() {
    jdbc.sql("DELETE FROM rate_limit_buckets").update();
  }

  @Test
  void negotiatesAndActuallySendsServerHeartbeatsWhileIdle() throws Exception {
    try (Browser browser = new Browser();
        Socket socket = browser.socket(true, "0,1000")) {
      long deadline = System.nanoTime() + Duration.ofSeconds(22).toNanos();
      while (socket.heartbeats.get() == 0 && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertThat(socket.heartbeats.get()).isGreaterThan(0);
    }
  }

  @Test
  void closesPhysicalTransportOnDisconnectWithReceiptAndPreservesSocketLimit() throws Exception {
    try (Browser browser = new Browser();
        Socket disconnected = browser.socket(true);
        Socket secondSocket = browser.socket(true);
        Socket thirdSocket = browser.socket(true);
        Socket excess = browser.transport()) {
      excess.assertClosed();
      assertThat(excess.closeStatus).isEqualTo(1008);
      disconnected.send("DISCONNECT\nreceipt:bye\n\n\0");
      disconnected.assertClosed();
      assertThat(disconnected.closeStatus).isEqualTo(1000);
      try (Socket replacement = browser.socket(true)) {
        replacement.subscribe("map", "/user/queue/map");
        assertThat(replacement.message("map")).contains("points");
        assertThat(secondSocket.closed).isFalse();
        assertThat(thirdSocket.closed).isFalse();
      }
    }
  }

  @Test
  void acceptsStompHandshakeCommandWithTheSameCsrfProtectionAsConnect() throws Exception {
    try (Browser browser = new Browser()) {
      try (Socket socket = browser.socket(true, "0,0", "STOMP")) {
        socket.subscribe("map", "/user/queue/map");
        assertThat(socket.message("map")).contains("points");
      }
      try (Socket socket = browser.socket(false, "0,0", "STOMP")) {
        assertThat(socket.await(frame -> frame.startsWith("ERROR"))).startsWith("ERROR");
        socket.assertClosed();
      }
    }
  }

  @Test
  void deliversCommittedChangesFromTheOtherInstanceAndKeepsListenerOutsidePool() throws Exception {
    try (Browser browser = new Browser()) {
      browser.login("STORE");
      try (Socket socket = browser.socket(true)) {
        socket.subscribe("events", "/user/queue/events");
        String before = socket.message("events");
        long received = first.getBean(PostgresChangeBridge.class).notifications();
        JsonNode created = browser.create(secondPort);
        String changed = socket.message("events");
        assertThat(changed).doesNotContain(before.substring(before.indexOf("eventId")));
        assertThat(first.getBean(PostgresChangeBridge.class).notifications())
            .isGreaterThan(received);
        socket.subscribe("order", "/topic/orders/" + created.path("id").asString());
        String order = socket.message("order");
        assertThat(order).contains(created.path("id").asString(), "SEARCHING", "timeline");
        assertThat(
                jdbc.sql(
                        "SELECT count(*) FROM pg_stat_activity WHERE application_name='bora-realtime-listen'")
                    .query(Long.class)
                    .single())
            .isEqualTo(2);
        assertThat(
                first
                    .getBean(javax.sql.DataSource.class)
                    .unwrap(com.zaxxer.hikari.HikariDataSource.class)
                    .getMaximumPoolSize())
            .isEqualTo(5);
      }
    }
  }

  @Test
  void rejectsMissingCsrfConnectAndClientPublicationToBroker() throws Exception {
    try (Browser browser = new Browser()) {
      browser.csrf();
      try (Socket socket = browser.socket(false)) {
        assertThat(socket.await(s -> s.startsWith("ERROR"))).startsWith("ERROR");
      }
      try (Socket socket = browser.socket(true)) {
        socket.send("SEND\ndestination:/topic/orders/00000000-0000-4000-8000-000000000001\n\n{}\0");
        assertThat(socket.await(s -> s.startsWith("ERROR"))).startsWith("ERROR");
      }
    }
  }

  @Test
  void deniesForeignOrderSubscriptionsAndAnonymousPrivateQueues() throws Exception {
    try (Browser owner = new Browser();
        Browser outsider = new Browser();
        Browser anonymous = new Browser()) {
      owner.login("STORE");
      String order = owner.create(secondPort).path("id").asString();
      outsider.login("OPERATIONS");
      try (Socket socket = outsider.socket(true)) {
        socket.subscribe("foreign", "/topic/orders/" + order);
        assertThat(socket.await(s -> s.startsWith("ERROR"))).startsWith("ERROR");
      }
      anonymous.csrf();
      try (Socket socket = anonymous.socket(true)) {
        socket.subscribe("private", "/user/queue/events");
        assertThat(socket.await(s -> s.startsWith("ERROR"))).startsWith("ERROR");
      }
    }
  }

  @Test
  void closesExistingSocketAfterLogoutOnTheOtherInstance() throws Exception {
    try (Browser browser = new Browser()) {
      browser.login("STORE");
      try (Socket socket = browser.socket(true)) {
        socket.subscribe("events", "/user/queue/events");
        socket.message("events");
        assertThat(browser.post(secondPort, "/api/v1/auth/logout", "", null).statusCode())
            .isEqualTo(204);
        socket.assertClosed();
        assertThat(socket.frames.stream().noneMatch(s -> s.startsWith("MESSAGE"))).isTrue();
      }
    }
  }

  @Test
  void expiresIdleHttpSessionAndRevokedContextWithoutAnotherHttpRequest() throws Exception {
    try (Browser browser = new Browser()) {
      browser.login("STORE");
      try (Socket socket = browser.socket(true)) {
        socket.subscribe("events", "/user/queue/events");
        socket.message("events");
        jdbc.sql(
                "UPDATE spring_session SET last_access_time=0,expiry_time=0 WHERE principal_name=:name")
            .param("name", browser.user.path("id").asString())
            .update();
        socket.assertClosed();
      }
    }
    try (Browser browser = new Browser()) {
      browser.login("COURIER");
      try (Socket socket = browser.socket(true)) {
        socket.subscribe("offers", "/user/queue/offers");
        socket.message("offers");
        jdbc.sql(
                "UPDATE demo_contexts SET expires_at=created_at WHERE id=(SELECT context_id FROM users WHERE id=CAST(:id AS uuid))")
            .param("id", browser.user.path("id").asString())
            .update();
        socket.assertClosed();
      }
    }
  }

  @Test
  void reconcilesMissedNotificationsAndSnapshotsLatestStateAfterReconnect() throws Exception {
    try (Browser browser = new Browser()) {
      browser.login("STORE");
      JsonNode order;
      PostgresChangeBridge bridge = first.getBean(PostgresChangeBridge.class);
      try (Socket socket = browser.socket(true)) {
        socket.subscribe("events", "/user/queue/events");
        socket.message("events");
        bridge.stop();
        try {
          order = browser.create(secondPort);
          assertThat(socket.message("events")).contains("ORDERS_CHANGED");
        } finally {
          bridge.start();
        }
      }
      String id = order.path("id").asString();
      assertThat(
              browser
                  .post(
                      secondPort,
                      "/api/v1/orders/" + id + "/cancel",
                      "{\"expectedVersion\":0}",
                      null)
                  .statusCode())
          .isEqualTo(200);
      try (Socket reconnected = browser.socket(true)) {
        reconnected.subscribe("order", "/topic/orders/" + id);
        String latest = reconnected.message("order");
        assertThat(latest).contains("CANCELED", "\"version\":1");
      }
    }
  }

  @Test
  void filtersPublicViewportAndKeepsPrivateCouriersOutOfAnonymousFrames() throws Exception {
    UUID inside = courier(true, -46.6559, -23.5614);
    UUID outside = courier(true, -46.70, -23.60);
    UUID visitor = courier(false, -46.6558, -23.5615);
    try (Browser browser = new Browser()) {
      browser.csrf();
      JsonNode rest =
          JSON.readTree(
              browser
                  .get(
                      firstPort,
                      "/api/v1/map/snapshot?west=-46.66&south=-23.57&east=-46.65&north=-23.55")
                  .body());
      assertThat(rest.path("points").toString())
          .contains(inside.toString())
          .doesNotContain(outside.toString(), visitor.toString());
      try (Socket socket = browser.socket(true)) {
        socket.send(
            "SEND\ndestination:/app/viewport\n\n{\"west\":-46.66,\"south\":-23.57,\"east\":-46.65,\"north\":-23.55,\"scope\":\"PUBLIC\"}\0");
        socket.subscribe("map", "/user/queue/map");
        String frame = socket.message("map");
        assertThat(frame)
            .contains(inside.toString())
            .doesNotContain(outside.toString(), visitor.toString(), "confirmation", "publicCode");
        socket.send(
            "SEND\ndestination:/app/viewport\n\n{\"west\":-46.71,\"south\":-23.61,\"east\":-46.69,\"north\":-23.59,\"scope\":\"PUBLIC\"}\0");
        assertThat(socket.message("map"))
            .contains(outside.toString())
            .doesNotContain(inside.toString());
      }
    } finally {
      jdbc.sql("UPDATE couriers SET status='OFFLINE' WHERE id IN (:a,:b,:c)")
          .param("a", inside)
          .param("b", outside)
          .param("c", visitor)
          .update();
    }
  }

  @Test
  void authorizesAssignedCourierAcrossContextsAndRevokesAfterReassignment() throws Exception {
    try (Browser store = new Browser();
        Browser courier = new Browser();
        Browser unrelated = new Browser()) {
      store.login("STORE");
      UUID order = UUID.fromString(store.create(secondPort).path("id").asString());
      courier.login("COURIER");
      UUID courierId = UUID.fromString(courier.user.path("courierId").asString());
      unrelated.login("COURIER");
      jdbc.sql(
              "UPDATE couriers SET status='BUSY',location=ST_SetSRID(ST_MakePoint(-46.6559,-23.5614),4326)::geography,location_updated_at=statement_timestamp() WHERE id=:id")
          .param("id", courierId)
          .update();
      jdbc.sql(
              "UPDATE orders SET status='ASSIGNED',courier_id=:courier,assigned_at=statement_timestamp(),next_attempt_at=NULL,version=version+1 WHERE id=:id")
          .param("id", order)
          .param("courier", courierId)
          .update();
      try (Socket denied = unrelated.socket(true)) {
        denied.subscribe("foreign", "/topic/orders/" + order);
        assertThat(denied.await(s -> s.startsWith("ERROR"))).startsWith("ERROR");
      }
      try (Socket socket = courier.socket(true)) {
        socket.subscribe("work", "/topic/orders/" + order);
        String assigned = socket.message("work");
        JsonNode body = JSON.readTree(assigned.substring(assigned.indexOf("\n\n") + 2));
        assertThat(body.path("id").asString()).isEqualTo(order.toString());
        assertThat(body.path("confirmationCode").isNull()).isTrue();
        jdbc.sql(
                "UPDATE orders SET status='SEARCHING',courier_id=NULL,assigned_at=NULL,next_attempt_at=statement_timestamp(),version=version+1 WHERE id=:id")
            .param("id", order)
            .update();
        jdbc.sql(
                "INSERT INTO order_events(order_id,type,payload,actor,order_version) VALUES(:id,'ORDER_REASSIGNED','{}'::jsonb,'test',2)")
            .param("id", order)
            .update();
        assertThat(socket.frames.poll(1500, TimeUnit.MILLISECONDS)).isNull();
        socket.subscribe("again", "/topic/orders/" + order);
        assertThat(socket.await(s -> s.startsWith("ERROR"))).startsWith("ERROR");
      }
    }
  }

  @Test
  void hidesLegacySimulatedCourierOnPrivateWorkUntilANewPublicAssignment() throws Exception {
    UUID courier = courier(true, -46.6559, -23.5614);
    String box = "?west=-46.66&south=-23.57&east=-46.65&north=-23.55";
    try (Browser owner = new Browser();
        Browser outsider = new Browser();
        Browser anonymous = new Browser()) {
      owner.login("STORE");
      UUID order = UUID.fromString(owner.create(secondPort).path("id").asString());
      owner.login("OPERATIONS");
      outsider.login("OPERATIONS");
      UUID offer = UUID.randomUUID();
      jdbc.sql(
              "INSERT INTO offers(id,order_id,courier_id,status,eta_to_pickup_s,created_at,expires_at) VALUES(:id,:order,:courier,'PENDING',10,statement_timestamp(),statement_timestamp()+interval '30 seconds')")
          .param("id", offer)
          .param("order", order)
          .param("courier", courier)
          .update();
      jdbc.sql("UPDATE couriers SET status='OFFERED' WHERE id=:id").param("id", courier).update();
      assertThat(anonymous.get(firstPort, "/api/v1/map/snapshot" + box).body())
          .doesNotContain(courier.toString());
      assertThat(owner.get(firstPort, "/api/v1/ops/snapshot" + box).body())
          .contains(courier.toString());
      assertThat(outsider.get(firstPort, "/api/v1/ops/snapshot" + box).body())
          .doesNotContain(courier.toString());
      jdbc.sql(
              "UPDATE offers SET status='ACCEPTED',responded_at=statement_timestamp() WHERE id=:id")
          .param("id", offer)
          .update();
      jdbc.sql(
              "UPDATE orders SET status='ASSIGNED',courier_id=:courier,assigned_at=statement_timestamp(),next_attempt_at=NULL,version=version+1 WHERE id=:id")
          .param("id", order)
          .param("courier", courier)
          .update();
      jdbc.sql("UPDATE couriers SET status='BUSY' WHERE id=:id").param("id", courier).update();
      assertThat(anonymous.get(firstPort, "/api/v1/map/snapshot" + box).body())
          .doesNotContain(courier.toString());
      assertThat(owner.get(firstPort, "/api/v1/ops/snapshot" + box).body())
          .contains(courier.toString());
      assertThat(outsider.get(firstPort, "/api/v1/ops/snapshot" + box).body())
          .doesNotContain(courier.toString());
      jdbc.sql(
              "UPDATE orders SET status='SEARCHING',courier_id=NULL,assigned_at=NULL,next_attempt_at=statement_timestamp(),version=version+1 WHERE id=:id")
          .param("id", order)
          .update();
      assertThat(anonymous.get(firstPort, "/api/v1/map/snapshot" + box).body())
          .doesNotContain(courier.toString());
      jdbc.sql(
              "UPDATE orders SET status='CANCELED',canceled_at=statement_timestamp(),next_attempt_at=NULL,version=version+1 WHERE id=:id")
          .param("id", order)
          .update();
      assertThat(anonymous.get(firstPort, "/api/v1/map/snapshot" + box).body())
          .doesNotContain(courier.toString());
      jdbc.sql(
              "UPDATE orders SET status='ASSIGNED',courier_id=:courier,assigned_at=statement_timestamp(),canceled_at=NULL,next_attempt_at=NULL,version=version+1 WHERE id=:id")
          .param("id", order)
          .param("courier", courier)
          .update();
      jdbc.sql(
              "UPDATE orders SET status='DELIVERED',at_store_at=statement_timestamp(),picked_up_at=statement_timestamp(),delivered_at=statement_timestamp(),version=version+1 WHERE id=:id")
          .param("id", order)
          .update();
      assertThat(anonymous.get(firstPort, "/api/v1/map/snapshot" + box).body())
          .doesNotContain(courier.toString());
      assertThat(owner.get(firstPort, "/api/v1/ops/snapshot" + box).body())
          .doesNotContain(courier.toString());
      UUID publicOrder = UUID.randomUUID();
      jdbc.sql(
              """
          INSERT INTO orders(id,public_code,store_id,dropoff,status,courier_id,created_at,sla_deadline,assigned_at)
          SELECT :id,:code,s.id,s.location,'ASSIGNED',:courier,statement_timestamp(),
            statement_timestamp()+interval '15 minutes',statement_timestamp()
          FROM stores s WHERE s.simulated ORDER BY s.id LIMIT 1
          """)
          .param("id", publicOrder)
          .param("code", UUID.randomUUID().toString().replace("-", ""))
          .param("courier", courier)
          .update();
      jdbc.sql(
              "INSERT INTO offers(id,order_id,courier_id,status,eta_to_pickup_s,created_at,expires_at,responded_at) VALUES(:id,:order,:courier,'ACCEPTED',10,statement_timestamp(),statement_timestamp()+interval '30 seconds',statement_timestamp())")
          .param("id", UUID.randomUUID())
          .param("order", publicOrder)
          .param("courier", courier)
          .update();
      assertThat(anonymous.get(firstPort, "/api/v1/map/snapshot" + box).body())
          .contains(courier.toString());
    } finally {
      jdbc.sql("UPDATE couriers SET status='OFFLINE' WHERE id=:id").param("id", courier).update();
    }
  }

  @Test
  void exposesOnlyTheLimitedCapabilityTrackingSnapshot() throws Exception {
    try (Browser browser = new Browser();
        Browser anonymous = new Browser()) {
      browser.login("STORE");
      JsonNode order = browser.create(secondPort);
      String code = order.path("publicCode").asString();
      String path = "/api/v1/tracking/" + code;
      HttpResponse<String> tracked = anonymous.get(secondPort, path);
      assertThat(tracked.statusCode()).isEqualTo(200);
      assertThat(tracked.body())
          .contains("SEARCHING", "pickup", "dropoff")
          .doesNotContain("confirmation", "owner", "userId", "context", "timeline", "publicCode");
      assertThat(anonymous.get(secondPort, "/api/v1/tracking/not-enumerable").statusCode())
          .isEqualTo(404);
      anonymous.csrf();
      try (Socket socket = anonymous.socket(true)) {
        socket.subscribe("tracking", "/topic/tracking/" + code);
        assertThat(socket.message("tracking")).contains("SEARCHING").doesNotContain("confirmation");
      }
    }
  }

  UUID courier(boolean simulated, double longitude, double latitude) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO couriers(id,name,status,simulated,location,location_updated_at) VALUES(:id,'Realtime test','AVAILABLE',:simulated,ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,clock_timestamp())")
        .param("id", id)
        .param("simulated", simulated)
        .param("lon", longitude)
        .param("lat", latitude)
        .update();
    return id;
  }

  static final class Socket implements WebSocket.Listener, AutoCloseable {
    final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    final AtomicBoolean closed = new AtomicBoolean();
    final AtomicInteger heartbeats = new AtomicInteger();
    final StringBuilder incoming = new StringBuilder();
    WebSocket socket;
    int closeStatus;

    @Override
    public void onOpen(WebSocket socket) {
      this.socket = socket;
      socket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      if (data.toString().equals("\n")) {
        heartbeats.incrementAndGet();
      }
      incoming.append(data);
      int end;
      while ((end = incoming.indexOf("\0")) >= 0) {
        frames.add(incoming.substring(0, end).stripLeading());
        incoming.delete(0, end + 1);
      }
      socket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
      closeStatus = status;
      closed.set(true);
      return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
      closed.set(true);
    }

    void send(String frame) {
      socket.sendText(frame, true).join();
    }

    void subscribe(String id, String destination) {
      send("SUBSCRIBE\nid:" + id + "\ndestination:" + destination + "\n\n\0");
    }

    String message(String subscription) throws Exception {
      return await(
          s -> s.startsWith("MESSAGE") && s.contains("subscription:" + subscription + "\n"));
    }

    String await(Predicate<String> predicate) throws Exception {
      long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
      List<String> seen = new ArrayList<>();
      while (System.nanoTime() < deadline) {
        String frame = frames.poll(200, TimeUnit.MILLISECONDS);
        if (frame != null) {
          if (predicate.test(frame)) {
            return frame;
          }
          seen.add(frame);
        }
      }
      throw new AssertionError("Quadro esperado ausente. Recebidos: " + seen);
    }

    void assertClosed() throws Exception {
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (!closed.get() && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertThat(closed).isTrue();
    }

    @Override
    public void close() {
      if (socket != null) {
        socket.abort();
      }
    }
  }

  final class Browser implements AutoCloseable {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    JsonNode user;

    String csrf() throws Exception {
      return JSON.readTree(get(firstPort, "/api/v1/auth/csrf").body()).path("token").asString();
    }

    void login(String role) throws Exception {
      HttpResponse<String> response =
          post(firstPort, "/api/v1/auth/demo", "{\"role\":\"" + role + "\"}", null);
      assertThat(response.statusCode()).isEqualTo(200);
      user = JSON.readTree(response.body()).path("user");
    }

    JsonNode create(int port) throws Exception {
      HttpResponse<String> response =
          post(
              port,
              "/api/v1/orders",
              "{\"dropoff\":{\"latitude\":-23.5622,\"longitude\":-46.6549}}",
              UUID.randomUUID().toString());
      assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
      return JSON.readTree(response.body());
    }

    HttpResponse<String> get(int port, String path) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(int port, String path, String body, String key) throws Exception {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .header("Content-Type", "application/json")
              .header("X-CSRF-TOKEN", csrf())
              .POST(HttpRequest.BodyPublishers.ofString(body));
      if (key != null) {
        request.header("Idempotency-Key", key);
      }
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    Socket socket(boolean validCsrf) throws Exception {
      return socket(validCsrf, "0,0");
    }

    Socket socket(boolean validCsrf, String heartbeat) throws Exception {
      return socket(validCsrf, heartbeat, "CONNECT");
    }

    Socket socket(boolean validCsrf, String heartbeat, String command) throws Exception {
      String token = csrf();
      Socket listener = transport();
      listener.send(
          command
              + "\naccept-version:1.2\nheart-beat:"
              + heartbeat
              + "\n"
              + (validCsrf ? "X-CSRF-TOKEN:" + token + "\n" : "")
              + "\n\0");
      if (validCsrf) {
        assertThat(listener.await(s -> s.startsWith("CONNECTED")))
            .startsWith("CONNECTED")
            .contains("heart-beat:10000,10000");
      }
      return listener;
    }

    Socket transport() {
      Socket listener = new Socket();
      client
          .newWebSocketBuilder()
          .header("Origin", "http://localhost:" + firstPort)
          .buildAsync(URI.create("ws://localhost:" + firstPort + "/ws"), listener)
          .join();
      return listener;
    }

    @Override
    public void close() {
      client.close();
    }
  }
}
