package dev.bora;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bora.routing.GeoPoint;
import dev.bora.routing.RoutingService;
import dev.bora.routing.RoutingTools;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentitySessionsIT {

  private static final String MANIFEST = "routing/sp-fixture-manifest.json";
  private static final Path GRAPHS = Path.of("target", "routing-identity-it").toAbsolutePath();

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    RoutingTools.prepareGraph(MANIFEST, GRAPHS);
    registry.add("bora.routing.graph-root", GRAPHS::toString);
    registry.add("bora.routing.manifest", () -> MANIFEST);
    registry.add("spring.session.jdbc.initialize-schema", () -> "never");
    registry.add("spring.session.timeout", () -> "2h");
    registry.add("server.servlet.session.cookie.secure", () -> "false");
  }

  @Autowired JdbcClient jdbc;
  @Autowired SessionRepository<? extends Session> sessions;
  @Autowired RoutingService routing;
  @LocalServerPort int port;
  private final JsonMapper json = JsonMapper.builder().build();
  private ConfigurableApplicationContext secondInstance;
  private int secondPort;

  @BeforeAll
  void startSecondInstanceAgainstSameDatabase() {
    secondInstance =
        new SpringApplicationBuilder(BoraApplication.class)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--spring.session.jdbc.initialize-schema=never",
                "--spring.session.timeout=2h",
                "--server.servlet.session.cookie.secure=false",
                "--bora.routing.graph-root=" + GRAPHS,
                "--bora.routing.manifest=" + MANIFEST);
    secondPort = ((WebServerApplicationContext) secondInstance).getWebServer().getPort();
  }

  @AfterAll
  void closeSecondInstance() {
    if (secondInstance != null) {
      secondInstance.close();
    }
  }

  @BeforeEach
  void resetRateLimits() {
    jdbc.sql("DELETE FROM rate_limit_buckets").update();
  }

  @Test
  void exposesAnonymousSessionAndRequiresCsrfForLogin() throws Exception {
    try (Browser browser = new Browser()) {
      HttpResponse<String> me = browser.request(port, "GET", "/auth/me", null, null);
      assertThat(me.statusCode()).isEqualTo(200);
      assertThat(tree(me).path("authenticated").asBoolean()).isFalse();
      assertThat(tree(me).path("user").isNull()).isTrue();

      HttpResponse<String> denied =
          browser.request(port, "POST", "/auth/demo", "{\"role\":\"STORE\"}", null);
      assertThat(denied.statusCode()).isEqualTo(403);
      assertThat(tree(denied).path("code").asString()).isEqualTo("INVALID_CSRF");
      assertThat(denied.headers().firstValue("content-type").orElse(""))
          .contains("application/problem+json");

      String csrf = browser.csrf(port);
      HttpResponse<String> malformed =
          browser.request(port, "POST", "/auth/demo", "{\"role\":\"ADMIN\"}", csrf);
      assertThat(malformed.statusCode()).isEqualTo(400);
      assertThat(tree(malformed).path("code").asString()).isEqualTo("INVALID_REQUEST");
      HttpResponse<String> missing = browser.request(port, "POST", "/auth/demo", "{}", csrf);
      assertThat(missing.statusCode()).isEqualTo(400);
      assertThat(tree(missing).path("errors").get(0).path("field").asString()).isEqualTo("role");
    }
  }

  @Test
  void sharesJdbcSessionAndMaskedCsrfAcrossIndependentInstances() throws Exception {
    try (Browser browser = new Browser()) {
      String csrf = browser.csrf(port);
      HttpResponse<String> login =
          browser.request(secondPort, "POST", "/auth/demo", "{\"role\":\"STORE\"}", csrf);
      assertThat(login.statusCode()).isEqualTo(200);
      JsonNode user = tree(login).path("user");
      UUID id = UUID.fromString(user.path("id").asString());
      UUID store = UUID.fromString(user.path("storeId").asString());
      assertThat(user.path("role").asString()).isEqualTo("STORE");
      assertThat(user.path("courierId").isNull()).isTrue();
      assertThat(
              jdbc.sql("SELECT simulated FROM stores WHERE id=:id")
                  .param("id", store)
                  .query(Boolean.class)
                  .single())
          .isFalse();
      assertThat(routing.snap(new GeoPoint(-23.5614, -46.6559)).distanceMeters()).isLessThan(150);
      assertThat(
              tree(browser.request(port, "GET", "/auth/me", null, null))
                  .path("user")
                  .path("id")
                  .asString())
          .isEqualTo(id.toString());
      assertThat(
              jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name=:name")
                  .param("name", id.toString())
                  .query(Integer.class)
                  .single())
          .isEqualTo(1);
      assertThat(login.headers().allValues("set-cookie").toString())
          .contains("HttpOnly", "SameSite=Lax");
    }
  }

  @Test
  void rotatesSessionAndCsrfWhilePreservingContextAndRoleAccounts() throws Exception {
    try (Browser browser = new Browser()) {
      browser.login(port, "STORE");
      JsonNode storeUser = tree(browser.request(port, "GET", "/auth/me", null, null)).path("user");
      UUID userId = UUID.fromString(storeUser.path("id").asString());
      UUID context = context(userId);
      String oldCookie = browser.cookie();
      String oldCsrf = browser.csrf(port);

      HttpResponse<String> courier =
          browser.request(secondPort, "POST", "/auth/demo", "{\"role\":\"COURIER\"}", oldCsrf);
      assertThat(courier.statusCode()).isEqualTo(200);
      assertThat(context(UUID.fromString(tree(courier).path("user").path("id").asString())))
          .isEqualTo(context);
      assertThat(browser.cookie()).isNotEqualTo(oldCookie);
      try (HttpClient outsider = HttpClient.newHttpClient()) {
        HttpResponse<String> stale =
            outsider.send(
                HttpRequest.newBuilder(uri(port, "/auth/me"))
                    .header("Cookie", oldCookie)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tree(stale).path("authenticated").asBoolean()).isFalse();
      }
      HttpResponse<String> staleCsrf =
          browser.request(port, "POST", "/auth/demo", "{\"role\":\"OPERATIONS\"}", oldCsrf);
      assertThat(staleCsrf.statusCode()).isEqualTo(403);
      browser.login(secondPort, "STORE");
      JsonNode again = tree(browser.request(port, "GET", "/auth/me", null, null)).path("user");
      assertThat(again.path("id").asString()).isEqualTo(userId.toString());
      assertThat(again.path("storeId").asString()).isEqualTo(storeUser.path("storeId").asString());
      assertThat(
              jdbc.sql("SELECT count(*) FROM users WHERE context_id=:id")
                  .param("id", context)
                  .query(Integer.class)
                  .single())
          .isEqualTo(2);
    }
  }

  @Test
  void keepsDifferentVisitorsPrivateAndExpiresIdleAccounts() throws Exception {
    try (Browser first = new Browser();
        Browser other = new Browser()) {
      JsonNode firstUser = tree(first.login(port, "STORE")).path("user");
      JsonNode otherUser = tree(other.login(secondPort, "STORE")).path("user");
      UUID firstId = UUID.fromString(firstUser.path("id").asString());
      UUID firstContext = context(firstId);
      assertThat(firstUser.path("storeId").asString())
          .isNotEqualTo(otherUser.path("storeId").asString());
      assertThat(context(UUID.fromString(otherUser.path("id").asString())))
          .isNotEqualTo(firstContext);
      jdbc.sql("UPDATE demo_contexts SET expires_at=created_at WHERE id=:id")
          .param("id", firstContext)
          .update();
      assertThat(
              tree(first.request(secondPort, "GET", "/auth/me", null, null))
                  .path("authenticated")
                  .asBoolean())
          .isFalse();
      assertThat(
              tree(other.request(port, "GET", "/auth/me", null, null))
                  .path("authenticated")
                  .asBoolean())
          .isTrue();
    }
  }

  @Test
  void extendsAllRoleAccountsAndContextAfterAuthenticatedUse() throws Exception {
    try (Browser browser = new Browser()) {
      UUID user =
          UUID.fromString(tree(browser.login(port, "STORE")).path("user").path("id").asString());
      UUID context = context(user);
      browser.login(secondPort, "OPERATIONS");
      jdbc.sql(
              "UPDATE demo_contexts SET expires_at=CURRENT_TIMESTAMP + INTERVAL '1 minute' WHERE id=:id")
          .param("id", context)
          .update();
      jdbc.sql(
              "UPDATE users SET expires_at=CURRENT_TIMESTAMP + INTERVAL '1 minute' WHERE context_id=:id")
          .param("id", context)
          .update();
      assertThat(
              tree(browser.request(port, "GET", "/auth/me", null, null))
                  .path("authenticated")
                  .asBoolean())
          .isTrue();
      assertThat(
              jdbc.sql(
                      "SELECT expires_at > CURRENT_TIMESTAMP + INTERVAL '119 minutes' FROM demo_contexts WHERE id=:id")
                  .param("id", context)
                  .query(Boolean.class)
                  .single())
          .isTrue();
      assertThat(
              jdbc.sql(
                      "SELECT bool_and(expires_at > CURRENT_TIMESTAMP + INTERVAL '119 minutes') FROM users WHERE context_id=:id")
                  .param("id", context)
                  .query(Boolean.class)
                  .single())
          .isTrue();
    }
  }

  @Test
  void logoutRevokesContextAndInvalidatesSessionOnBothInstances() throws Exception {
    try (Browser browser = new Browser()) {
      UUID context =
          context(
              UUID.fromString(
                  tree(browser.login(port, "OPERATIONS")).path("user").path("id").asString()));
      HttpResponse<String> result =
          browser.request(secondPort, "POST", "/auth/logout", "{}", browser.csrf(port));
      assertThat(result.statusCode()).isEqualTo(204);
      assertThat(result.body()).isEmpty();
      assertThat(
              tree(browser.request(port, "GET", "/auth/me", null, null))
                  .path("authenticated")
                  .asBoolean())
          .isFalse();
      assertThat(
              jdbc.sql("SELECT expires_at <= CURRENT_TIMESTAMP FROM demo_contexts WHERE id=:id")
                  .param("id", context)
                  .query(Boolean.class)
                  .single())
          .isTrue();
      String newCsrf = browser.csrf(secondPort);
      HttpResponse<String> relogin =
          browser.request(port, "POST", "/auth/demo", "{\"role\":\"OPERATIONS\"}", newCsrf);
      assertThat(relogin.statusCode()).isEqualTo(200);
      assertThat(context(UUID.fromString(tree(relogin).path("user").path("id").asString())))
          .isNotEqualTo(context);
    }
  }

  @Test
  void returnsSharedRateLimitWithRetryAfter() throws Exception {
    try (Browser browser = new Browser()) {
      for (int attempt = 0; attempt < 10; attempt++) {
        assertThat(browser.login(attempt % 2 == 0 ? port : secondPort, "OPERATIONS").statusCode())
            .isEqualTo(200);
      }
      HttpResponse<String> denied = browser.login(port, "STORE");
      assertThat(denied.statusCode()).isEqualTo(429);
      assertThat(tree(denied).path("code").asString()).isEqualTo("RATE_LIMIT_EXCEEDED");
      assertThat(Integer.parseInt(denied.headers().firstValue("retry-after").orElseThrow()))
          .isPositive();
    }
  }

  @Test
  void lateAnonymousReadCannotRevertLoginOrRestoreTheOldSession() throws Exception {
    try (Browser browser = new Browser()) {
      String csrf = browser.csrf(port);
      String oldId = browser.sessionId();
      String oldPrimary = sessionPrimary(oldId);
      // Reproduz a leitura pública iniciada antes do login e finalizada depois dele.
      Runnable finishOldRead = deferredReadCommit(sessions, oldId, true);

      HttpResponse<String> login =
          browser.request(secondPort, "POST", "/auth/demo", "{\"role\":\"STORE\"}", csrf);
      assertThat(login.statusCode()).isEqualTo(200);
      String user = tree(login).path("user").path("id").asString();
      String authenticatedId = browser.sessionId();
      finishOldRead.run();

      assertThat(sessions.findById(authenticatedId)).isNotNull();
      assertThat(sessions.findById(oldId)).isNull();
      assertThat(sessionPrimary(authenticatedId)).isNotEqualTo(oldPrimary);
      // A consulta CSRF subsequente não pode trocar o cookie por uma sessão anônima.
      browser.csrf(port);
      for (int target : new int[] {port, secondPort}) {
        JsonNode me = tree(browser.request(target, "GET", "/auth/me", null, null));
        assertThat(me.path("authenticated").asBoolean()).isTrue();
        assertThat(me.path("user").path("id").asString()).isEqualTo(user);
        assertThat(me.path("user").path("role").asString()).isEqualTo("STORE");
      }
    }
  }

  @Test
  void pausedOldReadCannotUndoRoleMigrationOrResurrectLogout() throws Exception {
    try (Browser browser = new Browser()) {
      JsonNode store = tree(browser.login(port, "STORE")).path("user");
      UUID originalContext = context(UUID.fromString(store.path("id").asString()));
      String oldId = browser.sessionId();
      String csrf = browser.csrf(port);
      // Cobre também a pausa entre carregar a sessão antiga e registrar o acesso.
      Runnable finishOldRead = deferredReadCommit(sessions, oldId, false);
      HttpResponse<String> switched =
          browser.request(secondPort, "POST", "/auth/demo", "{\"role\":\"COURIER\"}", csrf);
      assertThat(switched.statusCode()).isEqualTo(200);
      String courier = tree(switched).path("user").path("id").asString();
      String migratedId = browser.sessionId();
      finishOldRead.run();

      assertThat(sessions.findById(migratedId)).isNotNull();
      assertThat(sessions.findById(oldId)).isNull();
      assertThat(context(UUID.fromString(courier))).isEqualTo(originalContext);
      for (int target : new int[] {port, secondPort}) {
        JsonNode me = tree(browser.request(target, "GET", "/auth/me", null, null));
        assertThat(me.path("authenticated").asBoolean()).isTrue();
        assertThat(me.path("user").path("id").asString()).isEqualTo(courier);
        assertThat(me.path("user").path("role").asString()).isEqualTo("COURIER");
      }

      String logoutCsrf = browser.csrf(secondPort);
      Runnable finishReadAfterLogout = deferredReadCommit(sessions, migratedId, false);
      HttpResponse<String> logout = browser.request(port, "POST", "/auth/logout", "{}", logoutCsrf);
      assertThat(logout.statusCode()).isEqualTo(204);
      finishReadAfterLogout.run();
      assertThat(sessions.findById(migratedId)).isNull();
      assertThat(sessions.findById(oldId)).isNull();
      for (int target : new int[] {port, secondPort}) {
        assertThat(
                tree(browser.request(target, "GET", "/auth/me", null, null))
                    .path("authenticated")
                    .asBoolean())
            .isFalse();
      }
      assertThat(
              jdbc.sql("SELECT expires_at <= CURRENT_TIMESTAMP FROM demo_contexts WHERE id=:id")
                  .param("id", originalContext)
                  .query(Boolean.class)
                  .single())
          .isTrue();
    }
  }

  private static <S extends Session> Runnable deferredReadCommit(
      SessionRepository<S> repository, String sessionId, boolean touchBeforeRotation) {
    S loaded = repository.findById(sessionId);
    assertThat(loaded).isNotNull();
    if (touchBeforeRotation) {
      loaded.setLastAccessedTime(Instant.now());
    }
    return () -> {
      if (!touchBeforeRotation) {
        loaded.setLastAccessedTime(Instant.now());
      }
      repository.save(loaded);
    };
  }

  private String sessionPrimary(String sessionId) {
    return jdbc.sql("SELECT primary_id FROM spring_session WHERE session_id=:id")
        .param("id", sessionId)
        .query(String.class)
        .single();
  }

  private UUID context(UUID user) {
    return jdbc.sql("SELECT context_id FROM users WHERE id=:id")
        .param("id", user)
        .query(UUID.class)
        .single();
  }

  private JsonNode tree(HttpResponse<String> response) {
    return json.readTree(response.body());
  }

  private static URI uri(int port, String path) {
    return URI.create("http://localhost:" + port + "/api/v1" + path);
  }

  private final class Browser implements AutoCloseable {
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    String csrf(int target) throws Exception {
      HttpResponse<String> response = request(target, "GET", "/auth/csrf", null, null);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(tree(response).path("headerName").asString()).isEqualTo("X-CSRF-TOKEN");
      return tree(response).path("token").asString();
    }

    HttpResponse<String> login(int target, String role) throws Exception {
      return request(target, "POST", "/auth/demo", "{\"role\":\"" + role + "\"}", csrf(target));
    }

    HttpResponse<String> request(int target, String method, String path, String body, String csrf)
        throws Exception {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(uri(target, path)).timeout(Duration.ofSeconds(10));
      if (csrf != null) {
        request.header("X-CSRF-TOKEN", csrf);
      }
      if (body != null) {
        request.header("Content-Type", "application/json");
      }
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    String cookie() {
      return cookies.getCookieStore().getCookies().stream()
          .filter(cookie -> cookie.getName().equals("BORA_SESSION"))
          .map(cookie -> cookie.getName() + "=" + cookie.getValue())
          .findFirst()
          .orElseThrow();
    }

    String sessionId() {
      String encoded = cookie().substring("BORA_SESSION=".length()).replace("\"", "");
      return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
      client.close();
    }
  }
}
