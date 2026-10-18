package dev.bora.couriers.internal;

import dev.bora.couriers.PositionSample;
import dev.bora.routing.GeoPoint;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class CourierHttpFixture implements AutoCloseable {
  static final GeoPoint PICKUP = new GeoPoint(-23.5614, -46.6559);
  static final GeoPoint NEARBY = new GeoPoint(-23.56135, -46.6558);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpClient client =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
          .connectTimeout(Duration.ofSeconds(5))
          .build();
  private String csrf;

  HttpResponse<String> login(int port, String role) throws Exception {
    refresh(port);
    var response = post(port, "/auth/demo", "{\"role\":\"" + role + "\"}");
    refresh(port);
    return response;
  }

  void refresh(int port) throws Exception {
    csrf = json(get(port, "/auth/csrf")).path("token").asString();
  }

  HttpResponse<String> online(int port) throws Exception {
    return post(
        port,
        "/courier/availability",
        "{\"online\":true,\"position\":{\"latitude\":-23.5614,\"longitude\":-46.6559}}");
  }

  HttpResponse<String> positions(int port, List<PositionSample> samples) throws Exception {
    return post(
        port,
        "/courier/positions",
        JSON.writeValueAsString(java.util.Map.of("positions", samples)));
  }

  HttpResponse<String> get(int port, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(port, path)).timeout(Duration.ofSeconds(10)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> post(int port, String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(port, path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", csrf)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  static Instant recorded(HttpResponse<String> response) {
    return Instant.parse(json(response).path("locationUpdatedAt").asString());
  }

  private static URI uri(int port, String path) {
    return URI.create("http://localhost:" + port + "/api/v1" + path);
  }

  @Override
  public void close() {
    client.close();
  }
}
