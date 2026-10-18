package dev.bora.routing.internal;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.PMap;
import com.graphhopper.util.PointList;
import dev.bora.routing.GeoPoint;
import dev.bora.routing.Route;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingException.Reason;
import dev.bora.routing.RoutingService;
import dev.bora.routing.SnappedPoint;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GraphHopperRoutingEngine implements RoutingService, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(GraphHopperRoutingEngine.class);
  private final RoutingGraphSpec spec;
  private final Path directory;
  private final double simulationSpeed;
  private final double maxSnapDistanceMeters;
  private volatile LoadedGraph loaded;

  GraphHopperRoutingEngine(
      RoutingGraphSpec spec, Path graphRoot, double simulationSpeed, double maxSnapDistanceMeters) {
    if (!Double.isFinite(simulationSpeed) || simulationSpeed <= 0 || simulationSpeed > 100) {
      throw new IllegalArgumentException("O fator de velocidade precisa estar entre 0 e 100.");
    }
    if (!Double.isFinite(maxSnapDistanceMeters) || maxSnapDistanceMeters <= 0) {
      throw new IllegalArgumentException("O limite de encaixe precisa ser positivo.");
    }
    this.spec = Objects.requireNonNull(spec, "spec");
    this.directory = graphRoot.toAbsolutePath().normalize().resolve(spec.graphKey());
    this.simulationSpeed = simulationSpeed;
    this.maxSnapDistanceMeters = maxSnapDistanceMeters;
  }

  synchronized void initialize() {
    if (loaded != null) {
      return;
    }
    if (!spec.isComplete(directory)) {
      LOG.warn("Grafo de roteamento ausente ou incompleto; readiness indisponível.");
      return;
    }
    GraphHopper candidate = null;
    try {
      candidate = loadAndWarm(spec, directory);
      GraphHopper hopper = candidate;
      DefaultSnapFilter filter =
          new DefaultSnapFilter(
              hopper.createWeighting(hopper.getProfile(RoutingGraphSpec.PROFILE), new PMap()),
              hopper
                  .getEncodingManager()
                  .getBooleanEncodedValue(Subnetwork.key(RoutingGraphSpec.PROFILE)));
      loaded = new LoadedGraph(hopper, filter);
      LOG.info("Grafo de roteamento carregado e aquecido.");
    } catch (RuntimeException exception) {
      if (candidate != null) {
        candidate.close();
      }
      LOG.error("Falha ao carregar o grafo de roteamento; readiness indisponível.", exception);
    }
  }

  static GraphHopper loadAndWarm(RoutingGraphSpec spec, Path directory) {
    GraphHopper hopper = spec.createHopper(directory, false, null);
    try {
      if (!hopper.load()) {
        throw new IllegalStateException("Grafo de roteamento não encontrado.");
      }
      for (RoutingGraphSpec.WarmupPair pair : spec.warmupPairs()) {
        GHResponse response = hopper.route(request(pair.origin(), pair.destination()));
        if (response.hasErrors() || response.getBest().getPoints().isEmpty()) {
          throw new IllegalStateException("A rota de aquecimento não pôde ser calculada.");
        }
      }
      return hopper;
    } catch (RuntimeException exception) {
      hopper.close();
      throw exception;
    }
  }

  boolean isReady() {
    return loaded != null;
  }

  @Override
  public Route route(GeoPoint origin, GeoPoint destination) {
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(destination, "destination");
    LoadedGraph graph = requireReady();
    // Recusa pontos distantes antes de iniciar a busca no grafo.
    snap(graph, origin);
    snap(graph, destination);
    GHResponse response = graph.hopper().route(request(origin, destination));
    if (response.hasErrors()) {
      throw new RoutingException(Reason.NO_ROUTE, "Não há rota entre os pontos informados.");
    }
    ResponsePath path = response.getBest();
    PointList points = path.getPoints();
    if (points.isEmpty()) {
      throw new RoutingException(Reason.NO_ROUTE, "Não há rota entre os pontos informados.");
    }
    List<GeoPoint> geometry = new ArrayList<>(points.size());
    for (int index = 0; index < points.size(); index++) {
      geometry.add(new GeoPoint(points.getLat(index), points.getLon(index)));
    }
    SnappedPoint matchedOrigin = matched(origin, geometry.getFirst());
    SnappedPoint matchedDestination = matched(destination, geometry.getLast());
    long millis = path.getTime();
    return new Route(
        geometry,
        path.getDistance(),
        Duration.ofMillis(millis),
        Duration.ofMillis((long) Math.ceil(millis / simulationSpeed)),
        matchedOrigin,
        matchedDestination);
  }

  @Override
  public SnappedPoint snap(GeoPoint point) {
    return snap(requireReady(), Objects.requireNonNull(point, "point"));
  }

  private SnappedPoint snap(LoadedGraph graph, GeoPoint point) {
    Snap snap =
        graph
            .hopper()
            .getLocationIndex()
            .findClosest(point.latitude(), point.longitude(), graph.filter());
    if (!snap.isValid()) {
      throw new RoutingException(Reason.SNAP_TOO_FAR, "Não há via próxima do ponto informado.");
    }
    return matched(point, new GeoPoint(snap.getSnappedPoint().lat, snap.getSnappedPoint().lon));
  }

  private SnappedPoint matched(GeoPoint requested, GeoPoint point) {
    double distance =
        DistanceCalcEarth.DIST_EARTH.calcDist(
            requested.latitude(), requested.longitude(), point.latitude(), point.longitude());
    if (distance > maxSnapDistanceMeters) {
      throw new RoutingException(Reason.SNAP_TOO_FAR, "Não há via próxima do ponto informado.");
    }
    return new SnappedPoint(requested, point, distance);
  }

  static GHRequest request(GeoPoint origin, GeoPoint destination) {
    return new GHRequest(
            origin.latitude(), origin.longitude(), destination.latitude(), destination.longitude())
        .setProfile(RoutingGraphSpec.PROFILE)
        .setLocale("pt-BR")
        .setSnapPreventions(List.of())
        .putHint("instructions", false)
        .putHint("way_point_max_distance", 0);
  }

  private LoadedGraph requireReady() {
    LoadedGraph graph = loaded;
    if (graph == null) {
      throw new RoutingException(Reason.GRAPH_UNAVAILABLE, "O roteamento ainda está indisponível.");
    }
    return graph;
  }

  @Override
  public synchronized void close() {
    LoadedGraph graph = loaded;
    loaded = null;
    if (graph != null) {
      graph.hopper().close();
    }
  }

  private record LoadedGraph(GraphHopper hopper, DefaultSnapFilter filter) {}
}
