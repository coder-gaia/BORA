package dev.bora.simulator;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Um cliente HTTP compartilhado; cookies e CSRF continuam isolados por ator. */
final class SimulatorHttp implements AutoCloseable {
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final int MAX_IN_FLIGHT = 4;
  private static final String SECRET_HEADER = "X-Bora-Simulator-Key";
  private final SimulatorConfig config;
  private final SimulatorStats stats;
  private final ServerClock clock;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final HttpClient client;
  private final Semaphore requests = new Semaphore(MAX_IN_FLIGHT, true);

  SimulatorHttp(SimulatorConfig config, SimulatorStats stats, ServerClock clock) {
    this.config = config;
    this.stats = stats;
    this.clock = clock;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(executor)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
  }

  Reply scenario() throws IOException, InterruptedException {
    return exchange(null, "GET", "/api/v1/simulator/scenario", null, null, true);
  }

  Actor actor(String role, int index) {
    return new Actor(role, index);
  }

  private Reply exchange(
      Actor actor,
      String method,
      String path,
      Object body,
      String idempotencyKey,
      boolean privileged)
      throws IOException, InterruptedException {
    URI uri = config.baseUri().resolve(path);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(8))
            .header("Accept", "application/json")
            .header("User-Agent", "Bora-Simulator/1");
    if (privileged) {
      request.header(SECRET_HEADER, config.secret());
    }
    if (actor != null) {
      actor
          .cookies
          .get(uri, Map.of())
          .forEach((name, values) -> values.forEach(value -> request.header(name, value)));
      if (!method.equals("GET") && actor.csrfToken != null) {
        request.header(actor.csrfHeader, actor.csrfToken);
      }
    }
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    }
    HttpResponse<String> response;
    long queuedAt = System.nanoTime();
    requests.acquire();
    try {
      stats.requestStarted(System.nanoTime() - queuedAt);
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException failure) {
      stats.count("networkFailures");
      // Uma mutação pode ter chegado à API. O ator consulta o estado em vez de repeti-la aqui.
      throw failure;
    } finally {
      stats.requestFinished();
      requests.release();
    }
    stats.response(response.statusCode());
    if (actor != null) {
      actor.cookies.put(uri, response.headers().map());
      if (config.internalHttp()) {
        // O HTTPS termina no Caddy. Só a sessão própria segue pela origem da API na rede
        // interna explicitamente autorizada; domínio, caminho e os demais cookies não mudam.
        // CookieStore.get também filtra Secure em HTTP. A visão HTTPS abaixo serve só
        // para localizar a sessão dessa mesma origem; nenhuma conexão usa essa URI.
        URI secureCookieScope = URI.create("https:" + uri.getRawSchemeSpecificPart());
        actor.cookies.getCookieStore().get(secureCookieScope).stream()
            .filter(cookie -> cookie.getName().equals("BORA_SESSION"))
            .forEach(cookie -> cookie.setSecure(false));
      }
    }
    JsonNode node = JSON.createObjectNode();
    if (!response.body().isBlank()
        && response.headers().firstValue("content-type").orElse("").contains("json")) {
      node = JSON.readTree(response.body());
    }
    if (response.statusCode() >= 400) {
      String code = node.path("code").asString("");
      // Somente códigos simbólicos entram nas métricas; mensagens e payloads ficam privados.
      if (code.matches("[A-Z][A-Z0-9_]{0,63}")) {
        stats.count("apiError" + response.statusCode() + "_" + code);
      }
    }
    if (node.hasNonNull("serverTime")) {
      clock.synchronize(Instant.parse(node.path("serverTime").asString()));
    }
    long retryAfter = 1;
    try {
      retryAfter =
          Math.min(
              120,
              Math.max(
                  1, Long.parseLong(response.headers().firstValue("retry-after").orElse("1"))));
    } catch (NumberFormatException ignored) {
      // Os limites da API usam segundos; um cabeçalho inesperado mantém o recuo mínimo.
    }
    return new Reply(response.statusCode(), node, Duration.ofSeconds(retryAfter));
  }

  @Override
  public void close() {
    client.close();
    executor.close();
  }

  final class Actor {
    private final String role;
    private final int index;
    private final CookieManager cookies =
        new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private String csrfHeader;
    private String csrfToken;
    private boolean authenticated;

    private Actor(String role, int index) {
      this.role = role;
      this.index = index;
    }

    Reply get(String path) throws IOException, InterruptedException {
      return call("GET", path, null, null, false);
    }

    Reply post(String path, Object body) throws IOException, InterruptedException {
      return call("POST", path, body, null, path.startsWith("/api/v1/simulator/"));
    }

    Reply createOrder(Object body, String idempotencyKey) throws IOException, InterruptedException {
      return call("POST", "/api/v1/orders", body, idempotencyKey, false);
    }

    private Reply call(String method, String path, Object body, String key, boolean privileged)
        throws IOException, InterruptedException {
      if (!authenticated) {
        login();
      }
      Reply reply = exchange(this, method, path, body, key, privileged);
      if (reply.status() == 401) {
        authenticated = false;
        stats.count("sessionRenewals");
        login();
        return exchange(this, method, path, body, key, privileged);
      }
      if (reply.status() == 403 && reply.code().equals("INVALID_CSRF")) {
        refreshCsrf();
        stats.count("csrfRenewals");
        return exchange(this, method, path, body, key, privileged);
      }
      return reply;
    }

    private void login() throws IOException, InterruptedException {
      refreshCsrf();
      Reply login =
          exchange(
              this,
              "POST",
              "/api/v1/auth/simulator",
              Map.of("role", role, "index", index),
              null,
              true);
      if (login.status() != 200) {
        throw new ApiFailure(login.status(), login.code(), login.retryAfter());
      }
      refreshCsrf();
      authenticated = true;
      stats.count("actorLogins");
    }

    private void refreshCsrf() throws IOException, InterruptedException {
      Reply csrf = exchange(this, "GET", "/api/v1/auth/csrf", null, null, false);
      if (csrf.status() != 200 || !csrf.body().hasNonNull("token")) {
        throw new ApiFailure(csrf.status(), csrf.code(), csrf.retryAfter());
      }
      csrfHeader = csrf.body().path("headerName").asString();
      csrfToken = csrf.body().path("token").asString();
    }
  }

  record Reply(int status, JsonNode body, Duration retryAfter) {
    boolean successful() {
      return status >= 200 && status < 300;
    }

    String code() {
      return body.path("code").asString("UNKNOWN");
    }

    JsonNode requireSuccess() throws ApiFailure {
      if (!successful()) {
        throw new ApiFailure(status, code(), retryAfter);
      }
      return body;
    }
  }

  static final class ApiFailure extends IOException {
    final int status;
    final String code;
    final Duration retryAfter;

    ApiFailure(int status, String code, Duration retryAfter) {
      super("Resposta HTTP " + status + ": " + code);
      this.status = status;
      this.code = code;
      this.retryAfter = retryAfter;
    }
  }
}
