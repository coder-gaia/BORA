package dev.bora.routing.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.ConsoleAppender;
import com.graphhopper.util.Constants;
import dev.bora.routing.Route;
import dev.bora.routing.RoutingTools.GraphPreparation;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entrada das ferramentas; não cria contexto Spring nem conexão com banco. */
public final class RoutingToolRunner {
  private RoutingToolRunner() {}

  public static GraphPreparation prepareGraph(String manifestResource, Path graphRoot) {
    RoutingGraphImporter.ImportResult result =
        RoutingGraphImporter.prepare(RoutingGraphSpec.loadManifest(manifestResource), graphRoot);
    return new GraphPreparation(
        result.graphKey(),
        result.reused(),
        result.importMillis(),
        result.loadMillis(),
        result.graphBytes());
  }

  public static OptionalInt run(String[] args, PrintStream out, PrintStream err) {
    boolean requested =
        Arrays.stream(args)
            .anyMatch(
                value -> value.equals("--routing-import") || value.equals("--routing-benchmark"));
    if (!requested) {
      return OptionalInt.empty();
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("graphHopperVersion", Constants.VERSION);
    report.put("architecture", System.getProperty("os.arch"));
    report.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
    int code;
    try {
      Options options = Options.parse(args);
      configureLogging();
      RoutingGraphSpec spec = RoutingGraphSpec.loadManifest(options.manifest());
      report.put("tool", options.importGraph() ? "import" : "benchmark");
      report.put("graphKey", spec.graphKey());
      report.put("datasetSha256", spec.datasetSha256());
      if (options.importGraph()) {
        GraphPreparation result = prepareGraph(options.manifest(), options.graphRoot());
        report.put("reused", result.reused());
        report.put("importMillis", result.importMillis());
        report.put("loadMillis", result.loadMillis());
        report.put("graphBytes", result.graphBytes());
        report.put("success", true);
        code = 0;
      } else {
        code = benchmark(spec, options, report);
      }
    } catch (IllegalArgumentException exception) {
      report.put("success", false);
      report.put("error", exception.getMessage());
      code = 2;
    } catch (RuntimeException exception) {
      report.put("success", false);
      report.put("error", exception.getMessage());
      code = 1;
    }
    report.put("memory", memoryEvidence());
    try {
      out.println(RoutingGraphSpec.JSON.writeValueAsString(report));
    } catch (IOException exception) {
      err.println("Não foi possível escrever o relatório de roteamento.");
      return OptionalInt.of(1);
    }
    return OptionalInt.of(code);
  }

  private static int benchmark(RoutingGraphSpec spec, Options options, Map<String, Object> report) {
    try (GraphHopperRoutingEngine engine =
        new GraphHopperRoutingEngine(spec, options.graphRoot(), options.simulationSpeed(), 150)) {
      long loadStart = System.nanoTime();
      engine.initialize();
      report.put("loadMillis", Duration.ofNanos(System.nanoTime() - loadStart).toMillis());
      if (!engine.isReady()) {
        throw new IllegalStateException("Prepare o grafo antes de executar a medição.");
      }
      RoutingGraphSpec.WarmupPair samplePair = spec.warmupPairs().getFirst();
      Route sample = engine.route(samplePair.origin(), samplePair.destination());
      report.put(
          "sampleRoute",
          Map.of(
              "requestedOrigin", samplePair.origin(),
              "requestedDestination", samplePair.destination(),
              "snappedOrigin", sample.origin().matched(),
              "snappedDestination", sample.destination().matched(),
              "distanceMeters", sample.distanceMeters(),
              "durationMillis", sample.duration().toMillis(),
              "simulatedDurationMillis", sample.simulatedDuration().toMillis(),
              "geometryPoints", sample.geometry().size()));
      for (int index = 0; index < options.warmup(); index++) {
        RoutingGraphSpec.WarmupPair pair =
            spec.warmupPairs().get(index % spec.warmupPairs().size());
        engine.route(pair.origin(), pair.destination());
      }
      long[] nanos = new long[options.iterations()];
      for (int index = 0; index < nanos.length; index++) {
        RoutingGraphSpec.WarmupPair pair =
            spec.warmupPairs().get(index % spec.warmupPairs().size());
        long start = System.nanoTime();
        engine.route(pair.origin(), pair.destination());
        nanos[index] = System.nanoTime() - start;
      }
      Arrays.sort(nanos);
      double p95 = percentileMillis(nanos, 0.95);
      report.put("warmup", options.warmup());
      report.put("iterations", nanos.length);
      report.put("pairs", spec.warmupPairs().size());
      report.put("p50Millis", percentileMillis(nanos, 0.50));
      report.put("p95Millis", p95);
      report.put("maxMillis", nanos[nanos.length - 1] / 1_000_000d);
      report.put("maxP95Millis", options.maxP95Millis());
      report.put("simulationSpeed", options.simulationSpeed());
      try {
        report.put(
            "graphBytes",
            RoutingGraphImporter.directoryBytes(
                options.graphRoot().toAbsolutePath().normalize().resolve(spec.graphKey())));
      } catch (IOException exception) {
        throw new java.io.UncheckedIOException(exception);
      }
      boolean success = p95 < options.maxP95Millis();
      report.put("success", success);
      return success ? 0 : 1;
    }
  }

  private static double percentileMillis(long[] nanos, double quantile) {
    return nanos[Math.max(0, (int) Math.ceil(quantile * nanos.length) - 1)] / 1_000_000d;
  }

  private static Map<String, Object> memoryEvidence() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("processPeakRssBytes", processPeakRss());
    values.put("cgroupPeakBytes", optionalLong(Path.of("/sys/fs/cgroup/memory.peak")));
    Map<String, Long> events = new LinkedHashMap<>();
    Path eventFile = Path.of("/sys/fs/cgroup/memory.events");
    try {
      if (Files.isRegularFile(eventFile)) {
        for (String line : Files.readAllLines(eventFile)) {
          String[] parts = line.trim().split("\\s+");
          if (parts.length == 2 && (parts[0].equals("oom") || parts[0].equals("oom_kill"))) {
            events.put(parts[0], Long.parseLong(parts[1]));
          }
        }
      }
    } catch (IOException | NumberFormatException | SecurityException ignored) {
      // As evidências Linux são opcionais para permitir execução nativa em outros sistemas.
    }
    values.put("cgroupOom", events.get("oom"));
    values.put("cgroupOomKill", events.get("oom_kill"));
    return values;
  }

  private static Long processPeakRss() {
    Path status = Path.of("/proc/self/status");
    try {
      if (Files.isRegularFile(status)) {
        for (String line : Files.readAllLines(status)) {
          if (line.startsWith("VmHWM:")) {
            return Math.multiplyExact(Long.parseLong(line.trim().split("\\s+")[1]), 1024);
          }
        }
      }
    } catch (IOException
        | ArithmeticException
        | NumberFormatException
        | SecurityException ignored) {
      // /proc não existe no Windows e pode ter acesso restrito em outros ambientes.
    }
    return null;
  }

  private static Long optionalLong(Path path) {
    try {
      if (Files.isRegularFile(path)) {
        return Long.parseLong(Files.readString(path).trim());
      }
    } catch (IOException | NumberFormatException | SecurityException ignored) {
      // A ausência de contadores cgroup v2 não impede a execução das ferramentas.
    }
    return null;
  }

  private static void configureLogging() {
    if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
      var root = context.getLogger(Logger.ROOT_LOGGER_NAME);
      root.detachAndStopAllAppenders();
      PatternLayoutEncoder encoder = new PatternLayoutEncoder();
      encoder.setContext(context);
      encoder.setPattern("%level %msg%n");
      encoder.start();
      ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent> console = new ConsoleAppender<>();
      console.setContext(context);
      console.setTarget("System.err");
      console.setEncoder(encoder);
      console.start();
      root.setLevel(Level.INFO);
      root.addAppender(console);
    }
  }

  private record Options(
      boolean importGraph,
      String manifest,
      Path graphRoot,
      int warmup,
      int iterations,
      double maxP95Millis,
      double simulationSpeed) {
    static Options parse(String[] args) {
      boolean importing = args.length > 0 && args[0].equals("--routing-import");
      if (args.length == 0 || (!importing && !args[0].equals("--routing-benchmark"))) {
        throw new IllegalArgumentException("Informe primeiro o comando de roteamento.");
      }
      Map<String, String> values = new LinkedHashMap<>();
      for (int index = 1; index < args.length; index++) {
        String argument = args[index];
        int equals = argument.indexOf('=');
        String key = equals >= 0 ? argument.substring(0, equals) : argument;
        String value;
        if (equals >= 0) {
          value = argument.substring(equals + 1);
        } else if (index + 1 < args.length && !args[index + 1].startsWith("--")) {
          value = args[++index];
        } else {
          throw new IllegalArgumentException("Opção de roteamento sem valor: " + key);
        }
        if (!java.util.Set.of(
                    "--manifest", "--graph-root", "--warmup", "--iterations", "--max-p95-ms")
                .contains(key)
            || values.putIfAbsent(key, value) != null) {
          throw new IllegalArgumentException("Opção de roteamento inválida ou repetida: " + key);
        }
      }
      int warmup = Integer.parseInt(values.getOrDefault("--warmup", "100"));
      int iterations = Integer.parseInt(values.getOrDefault("--iterations", "500"));
      double maxP95 = Double.parseDouble(values.getOrDefault("--max-p95-ms", "50"));
      double speed = Double.parseDouble(environment("SIM_SPEED", "3"));
      if (warmup < 0
          || warmup > 50_000
          || iterations <= 0
          || iterations > 50_000
          || !Double.isFinite(maxP95)
          || maxP95 <= 0
          || !Double.isFinite(speed)
          || speed <= 0
          || speed > 100) {
        throw new IllegalArgumentException("Parâmetros de medição inválidos.");
      }
      return new Options(
          importing,
          values.getOrDefault("--manifest", "routing/data/manifest.json"),
          Path.of(
              values.getOrDefault(
                  "--graph-root", environment("BORA_GRAPH_ROOT", "target/routing-graphs"))),
          warmup,
          iterations,
          maxP95,
          speed);
    }

    private static String environment(String key, String fallback) {
      String value = System.getenv(key);
      return value == null || value.isBlank() ? fallback : value;
    }
  }
}
