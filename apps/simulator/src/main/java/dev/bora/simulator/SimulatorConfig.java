package dev.bora.simulator;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

record SimulatorConfig(
    URI baseUri,
    String secret,
    long seed,
    Duration duration,
    int healthPort,
    int courierCount,
    int storeCount,
    boolean internalHttp,
    Path reportPath) {
  SimulatorConfig {
    if (internalHttp && !allowedInternalOrigin(baseUri)) {
      throw new IllegalArgumentException("A exceção de cookie exige a origem HTTP interna da API.");
    }
  }

  @Override
  public String toString() {
    return "SimulatorConfig[baseUri="
        + baseUri
        + ", secret=[redacted], seed="
        + seed
        + ", duration="
        + duration
        + ", healthPort="
        + healthPort
        + ", courierCount="
        + courierCount
        + ", storeCount="
        + storeCount
        + ", internalHttp="
        + internalHttp
        + "]";
  }

  static SimulatorConfig from(Map<String, String> environment) {
    URI uri = URI.create(environment.getOrDefault("BORA_API_URL", "http://api:8080"));
    String secret = environment.getOrDefault("BORA_SIMULATOR_KEY", "");
    if (!java.util.Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || (!uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
      throw new IllegalArgumentException("Informe uma origem HTTP válida em BORA_API_URL.");
    }
    if (secret.length() < 32
        || secret.length() > 256
        || secret.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException(
          "Configure BORA_SIMULATOR_KEY com 32 a 256 caracteres sem espaços.");
    }
    long seconds = Long.parseLong(environment.getOrDefault("SIM_DURATION_SECONDS", "0"));
    int port = Integer.parseInt(environment.getOrDefault("SIM_HEALTH_PORT", "8090"));
    int couriers = Integer.parseInt(environment.getOrDefault("SIM_COURIER_COUNT", "120"));
    int stores = Integer.parseInt(environment.getOrDefault("SIM_STORE_COUNT", "40"));
    if (seconds < 0
        || port < 1
        || port > 65_535
        || couriers < 1
        || couriers > 120
        || stores < 1
        || stores > 40) {
      throw new IllegalArgumentException("Duração ou porta inválida.");
    }
    String report = environment.get("SIM_REPORT_PATH");
    String internalFlag = environment.getOrDefault("BORA_SIMULATOR_INTERNAL_HTTP", "false");
    if (!internalFlag.equalsIgnoreCase("true") && !internalFlag.equalsIgnoreCase("false")) {
      throw new IllegalArgumentException("BORA_SIMULATOR_INTERNAL_HTTP deve ser true ou false.");
    }
    boolean internalHttp = Boolean.parseBoolean(internalFlag);
    return new SimulatorConfig(
        uri,
        secret,
        Long.parseLong(environment.getOrDefault("SIM_SEED", "2026")),
        Duration.ofSeconds(seconds),
        port,
        couriers,
        stores,
        internalHttp,
        report == null || report.isBlank() ? null : Path.of(report));
  }

  private static boolean allowedInternalOrigin(URI uri) {
    String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
    return uri.getScheme().equals("http")
        && (host.equals("localhost")
            || host.equals("127.0.0.1")
            || (java.util.Set.of("api", "api1", "api2").contains(host) && uri.getPort() == 8080));
  }
}
