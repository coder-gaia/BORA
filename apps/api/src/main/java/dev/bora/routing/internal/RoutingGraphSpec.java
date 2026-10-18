package dev.bora.routing.internal;

import static com.graphhopper.json.Statement.If;
import static com.graphhopper.json.Statement.Op.LIMIT;
import static com.graphhopper.json.Statement.Op.MULTIPLY;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.util.Constants;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.TurnCostsConfig;
import dev.bora.routing.GeoPoint;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

final class RoutingGraphSpec {
  static final String PROFILE = "motorcycle";
  static final String COMPLETION_FILE = "bora-graph.json";
  static final ObjectMapper JSON = new ObjectMapper();
  private static final String ENCODED_VALUES = "motorcycle_access,car_average_speed";
  private static final String IGNORED_HIGHWAYS = "footway,construction,cycleway,path,steps";
  private static final long MAX_DATASET_BYTES = 30L * 1024 * 1024;
  private final String resource;
  private final String datasetResource;
  private final String datasetFile;
  private final String datasetSha256;
  private final long sizeBytes;
  private final List<WarmupPair> warmupPairs;
  private final int minNetworkSize;
  private final String graphKey;

  private RoutingGraphSpec(
      String resource,
      String datasetFile,
      String datasetSha256,
      long sizeBytes,
      List<WarmupPair> warmupPairs,
      int minNetworkSize) {
    this.resource = resource;
    this.datasetFile = datasetFile;
    this.datasetResource = resource.substring(0, resource.lastIndexOf('/') + 1) + datasetFile;
    this.datasetSha256 = datasetSha256;
    this.sizeBytes = sizeBytes;
    this.warmupPairs = List.copyOf(warmupPairs);
    this.minNetworkSize = minNetworkSize;
    String configuration =
        String.join(
            "\n",
            "bora-graph-format=1",
            "graphhopper=" + Constants.VERSION,
            "parser=" + MotorcycleImportRegistry.PARSER_VERSION,
            "dataset=" + datasetSha256,
            "profile=" + PROFILE,
            "access=block_private:true,block_fords:false",
            "turn_costs=motorcycle,motor_vehicle;u_turn_costs:60",
            "custom_model=distance_influence:0;priority:!motorcycle_access*0;speed:car_average_speed",
            "encoded_values=" + ENCODED_VALUES,
            "ignored_highways=" + IGNORED_HIGHWAYS,
            "min_network_size=" + minNetworkSize,
            "ch=true;workers=1;elevation=false;sort=true");
    this.graphKey = sha256(configuration.getBytes(StandardCharsets.UTF_8));
  }

  static RoutingGraphSpec loadManifest(String resource) {
    if (resource == null
        || !resource.matches("[a-zA-Z0-9_./-]+")
        || resource.startsWith("/")
        || resource.contains("..")) {
      throw new IllegalArgumentException("Recurso de manifesto inválido.");
    }
    try (InputStream input = openResource(resource)) {
      JsonNode manifest = JSON.readTree(input);
      String file = manifest.path("datasetFile").asText();
      String digest = manifest.path("sha256").asText();
      long bytes = manifest.path("sizeBytes").asLong(-1);
      int networkSize = manifest.path("minNetworkSize").asInt(200);
      if (!file.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")
          || !digest.matches("[a-f0-9]{64}")
          || bytes <= 0
          || bytes > MAX_DATASET_BYTES
          || networkSize < 0) {
        throw new IllegalArgumentException("Metadados do conjunto de vias inválidos.");
      }
      JsonNode bbox = manifest.path("bbox");
      if (!bbox.isArray() || bbox.size() != 4) {
        throw new IllegalArgumentException("Limites do conjunto de vias ausentes.");
      }
      GeoPoint southwest = new GeoPoint(number(bbox.get(1)), number(bbox.get(0)));
      GeoPoint northeast = new GeoPoint(number(bbox.get(3)), number(bbox.get(2)));
      if (southwest.latitude() >= northeast.latitude()
          || southwest.longitude() >= northeast.longitude()) {
        throw new IllegalArgumentException("Limites do conjunto de vias inválidos.");
      }
      List<WarmupPair> pairs = new ArrayList<>();
      for (JsonNode pair : manifest.path("warmupPairs")) {
        pairs.add(new WarmupPair(point(pair.path("origin")), point(pair.path("destination"))));
      }
      if (pairs.isEmpty() || pairs.size() > 100) {
        throw new IllegalArgumentException("O manifesto precisa informar rotas de aquecimento.");
      }
      return new RoutingGraphSpec(resource, file, digest, bytes, pairs, networkSize);
    } catch (IOException exception) {
      throw new UncheckedIOException("Não foi possível ler o manifesto de vias.", exception);
    }
  }

  GraphHopper createHopper(Path graphDirectory, boolean writable, Path osmFile) {
    CustomModel model =
        new CustomModel()
            .setDistanceInfluence(0d)
            .addToPriority(If("!motorcycle_access", MULTIPLY, "0"))
            .addToSpeed(If("true", LIMIT, "car_average_speed"));
    Profile profile =
        new Profile(PROFILE)
            .setCustomModel(model)
            .setTurnCostsConfig(new TurnCostsConfig(List.of("motorcycle", "motor_vehicle"), 60));
    GraphHopperConfig config =
        new GraphHopperConfig()
            .putObject("graph.location", graphDirectory.toString())
            .putObject("graph.dataaccess.default_type", writable ? "MMAP" : "MMAP_RO")
            .putObject("graph.sort", true)
            .putObject("graph.encoded_values", ENCODED_VALUES)
            .putObject("import.osm.ignored_highways", IGNORED_HIGHWAYS)
            .putObject("prepare.min_network_size", minNetworkSize)
            .putObject("prepare.ch.threads", 1)
            .putObject("datareader.worker_threads", 1)
            .putObject("routing.max_visited_nodes", 100_000)
            .putObject("routing.timeout_ms", 1_000)
            .putObject("routing.instructions", false)
            .setProfiles(List.of(profile))
            .setCHProfiles(List.of(new CHProfile(PROFILE)));
    if (osmFile != null) {
      config.putObject("datareader.file", osmFile.toString());
    }
    GraphHopper hopper = new GraphHopper();
    hopper.setImportRegistry(new MotorcycleImportRegistry());
    hopper.init(config);
    hopper.setAllowWrites(writable);
    return hopper;
  }

  boolean isComplete(Path directory) {
    Path marker = directory.resolve(COMPLETION_FILE);
    if (!java.nio.file.Files.isRegularFile(marker)) {
      return false;
    }
    try {
      JsonNode manifest = JSON.readTree(marker.toFile());
      return graphKey.equals(manifest.path("graphKey").asText())
          && datasetSha256.equals(manifest.path("datasetSha256").asText())
          && Constants.VERSION.equals(manifest.path("graphHopperVersion").asText());
    } catch (IOException exception) {
      return false;
    }
  }

  static InputStream openResource(String resource) {
    InputStream input = RoutingGraphSpec.class.getClassLoader().getResourceAsStream(resource);
    if (input == null) {
      throw new IllegalArgumentException("Recurso de vias não encontrado: " + resource);
    }
    return input;
  }

  private static GeoPoint point(JsonNode value) {
    return new GeoPoint(number(value.path("latitude")), number(value.path("longitude")));
  }

  private static double number(JsonNode value) {
    if (value == null || !value.isNumber()) {
      throw new IllegalArgumentException("Coordenada ausente no manifesto.");
    }
    return value.asDouble();
  }

  static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  static String sha256(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  String resource() {
    return resource;
  }

  String datasetResource() {
    return datasetResource;
  }

  String datasetFile() {
    return datasetFile;
  }

  String datasetSha256() {
    return datasetSha256;
  }

  long sizeBytes() {
    return sizeBytes;
  }

  String graphKey() {
    return graphKey;
  }

  List<WarmupPair> warmupPairs() {
    return warmupPairs;
  }

  record WarmupPair(GeoPoint origin, GeoPoint destination) {}
}
