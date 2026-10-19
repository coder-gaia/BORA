package dev.bora.identity.internal;

import dev.bora.identity.DemoRole;
import dev.bora.routing.GeoPoint;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingService;
import dev.bora.shared.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Catálogo determinístico sem gravações. A identidade só é aprovisionada no POST de entrada. */
@Service
class SimulatorDirectory {
  static final int COURIER_COUNT = 120;
  static final int STORE_COUNT = 40;
  private final JdbcClient jdbc;
  private final RoutingService routing;
  private final Clock clock;
  private final long seed;
  private final double speed;
  private volatile List<ActorView> storeCatalog;

  SimulatorDirectory(
      JdbcClient jdbc,
      RoutingService routing,
      Clock clock,
      @Value("${bora.simulator.seed:2026}") long seed,
      @Value("${bora.routing.simulation-speed:3}") double speed) {
    this.jdbc = jdbc;
    this.routing = routing;
    this.clock = clock;
    this.seed = seed;
    this.speed = speed;
  }

  ScenarioView scenario() {
    List<ActorView> catalog = stores();
    List<ActorView> couriers = new ArrayList<>(COURIER_COUNT);
    for (int index = 0; index < COURIER_COUNT; index++) {
      couriers.add(
          new ActorView(
              index,
              courierId(index),
              courierName(index),
              catalog.get((int) Math.floorMod(seed + index * 17L, STORE_COUNT)).position()));
    }
    return new ScenarioView(
        seed,
        speed,
        clock.instant(),
        couriers,
        catalog,
        catalog.stream().map(ActorView::position).toList());
  }

  ActorIdentity actor(DemoRole role, int index) {
    int bound = role == DemoRole.COURIER ? COURIER_COUNT : STORE_COUNT;
    if ((role != DemoRole.COURIER && role != DemoRole.STORE) || index < 0 || index >= bound) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "INVALID_SIMULATOR_ACTOR",
          "Escolha uma loja ou um entregador válido do simulador.");
    }
    ActorView view = role == DemoRole.STORE ? stores().get(index) : null;
    return new ActorIdentity(
        id("user:" + role + ":" + index),
        id("context:" + role + ":" + index),
        role,
        role == DemoRole.STORE ? view.id() : null,
        role == DemoRole.COURIER ? courierId(index) : null,
        role == DemoRole.STORE ? view.name() : courierName(index),
        role == DemoRole.STORE ? view.position() : null);
  }

  private List<ActorView> stores() {
    List<ActorView> current = storeCatalog;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (storeCatalog != null) {
        return storeCatalog;
      }
      List<ActorView> catalog = new ArrayList<>(STORE_COUNT);
      try {
        for (int index = 0; index < STORE_COUNT; index++) {
          var store = seedStore(index);
          GeoPoint position = routing.snap(store.location()).matched();
          if (!jdbc.sql(
                  """
              SELECT ST_Covers(polygon::geometry,ST_SetSRID(ST_MakePoint(:lon,:lat),4326))
              FROM service_areas WHERE id=:id
              """)
              .param("id", store.serviceAreaId())
              .param("lon", position.longitude())
              .param("lat", position.latitude())
              .query(Boolean.class)
              .optional()
              .orElse(false)) {
            throw unavailable();
          }
          catalog.add(new ActorView(index, store.id(), store.name(), position));
        }
      } catch (RoutingException failure) {
        throw new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "SIMULATOR_ROUTING_UNAVAILABLE",
            "O grafo da cidade ainda não está pronto para o simulador.");
      }
      storeCatalog = List.copyOf(catalog);
      return storeCatalog;
    }
  }

  private SeedStore seedStore(int index) {
    return jdbc.sql(
            """
        SELECT id,name,service_area_id,ST_Y(location::geometry) latitude,
          ST_X(location::geometry) longitude FROM stores WHERE id=:id AND simulated
        """)
        .param("id", storeId(index))
        .query(
            (row, ignored) ->
                new SeedStore(
                    row.getObject("id", UUID.class),
                    row.getString("name"),
                    new GeoPoint(row.getDouble("latitude"), row.getDouble("longitude")),
                    row.getObject("service_area_id", UUID.class)))
        .optional()
        .orElseThrow(SimulatorDirectory::unavailable);
  }

  static UUID courierId(int index) {
    return id("courier:" + index);
  }

  static UUID storeId(int index) {
    return UUID.fromString(
        "20000000-0000-4000-8000-" + String.format(Locale.ROOT, "%012d", index + 1));
  }

  private static UUID id(String suffix) {
    return UUID.nameUUIDFromBytes(("bora-simulator:" + suffix).getBytes(StandardCharsets.UTF_8));
  }

  private static String courierName(int index) {
    String[] first = {
      "Ari", "Bia", "Caio", "Dani", "Eli", "Fê", "Gui", "Iza", "Jo", "Kai", "Lu", "Max"
    };
    String[] last = {
      "Aurora", "Campo", "Cedro", "Lago", "Lima", "Mar", "Monte", "Pinho", "Sol", "Vale"
    };
    return first[index % first.length] + " " + last[index / first.length];
  }

  private static ApiException unavailable() {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "SIMULATOR_UNAVAILABLE",
        "O cenário do simulador ainda não está pronto.");
  }

  record ActorIdentity(
      UUID userId,
      UUID contextId,
      DemoRole role,
      UUID storeId,
      UUID courierId,
      String name,
      GeoPoint position) {}

  private record SeedStore(UUID id, String name, GeoPoint location, UUID serviceAreaId) {}

  @Schema(requiredProperties = {"index", "id", "name", "position"})
  record ActorView(int index, UUID id, String name, GeoPoint position) {}

  @Schema(
      requiredProperties = {"seed", "speed", "serverTime", "couriers", "stores", "destinations"})
  record ScenarioView(
      long seed,
      double speed,
      Instant serverTime,
      List<ActorView> couriers,
      List<ActorView> stores,
      List<GeoPoint> destinations) {
    ScenarioView {
      couriers = List.copyOf(couriers);
      stores = List.copyOf(stores);
      destinations = List.copyOf(destinations);
    }
  }
}
