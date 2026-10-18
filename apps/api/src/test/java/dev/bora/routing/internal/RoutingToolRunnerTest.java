package dev.bora.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bora.routing.RoutingTools;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutingToolRunnerTest {
  @TempDir Path root;

  @Test
  void leavesApplicationArgumentsUntouched() {
    assertThat(RoutingTools.run(new String[] {"--server.port=8081"})).isEmpty();
  }

  @Test
  void rejectsInvalidToolArgumentsWithStructuredEvidence() throws Exception {
    var result = execute("--routing-benchmark", "--iterations=0");
    assertThat(result.code()).isEqualTo(2);
    assertThat(result.json().path("success").asBoolean()).isFalse();
    assertThat(result.json().has("memory")).isTrue();
  }

  @Test
  void importsAndBenchmarksWithoutStartingSpringOrUsingDatabase() throws Exception {
    var imported =
        execute(
            "--routing-import",
            "--manifest=routing/synthetic-manifest.json",
            "--graph-root=" + root);
    assertThat(imported.code()).isZero();
    assertThat(imported.json().path("success").asBoolean()).isTrue();
    assertThat(imported.json().path("reused").asBoolean()).isFalse();
    assertThat(imported.json().path("graphBytes").asLong()).isPositive();
    var benchmark =
        execute(
            "--routing-benchmark",
            "--manifest=routing/synthetic-manifest.json",
            "--graph-root=" + root,
            "--warmup=5",
            "--iterations=20",
            "--max-p95-ms=100000");
    assertThat(benchmark.code()).isZero();
    assertThat(benchmark.json().path("iterations").asInt()).isEqualTo(20);
    assertThat(benchmark.json().path("p95Millis").asDouble()).isPositive();
    var sample = benchmark.json().path("sampleRoute");
    assertThat(sample.path("distanceMeters").asDouble()).isPositive();
    assertThat(sample.path("geometryPoints").asInt()).isGreaterThan(1);
    assertThat(sample.path("simulatedDurationMillis").asLong())
        .isEqualTo(
            (long)
                Math.ceil(
                    sample.path("durationMillis").asLong()
                        / benchmark.json().path("simulationSpeed").asDouble()));
    assertThat(benchmark.json().path("memory").has("processPeakRssBytes")).isTrue();
  }

  private static Result execute(String... args) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      int code = RoutingToolRunner.run(args, output, output).orElseThrow();
      return new Result(code, RoutingGraphSpec.JSON.readTree(bytes.toByteArray()));
    }
  }

  private record Result(int code, com.fasterxml.jackson.databind.JsonNode json) {}
}
