package dev.bora.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bora.routing.RoutingTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoutingGraphImporterTest {
  private static final String MANIFEST = "routing/synthetic-manifest.json";
  @TempDir Path root;

  @Test
  void concurrentPreparationImportsOnceAndThenReusesTheImmutableGraph() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch start = new CountDownLatch(1);
      var first =
          executor.submit(
              () -> {
                start.await();
                return RoutingTools.prepareGraph(MANIFEST, root);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return RoutingTools.prepareGraph(MANIFEST, root);
              });
      start.countDown();
      var results = java.util.List.of(first.get(), second.get());
      assertThat(results).filteredOn(result -> !result.reused()).hasSize(1);
      assertThat(results).filteredOn(RoutingTools.GraphPreparation::reused).hasSize(1);
      assertThat(results.getFirst().graphKey()).isEqualTo(results.getLast().graphKey());
      assertThat(results.getFirst().graphBytes()).isPositive();
      try (var children = Files.list(root)) {
        assertThat(children.map(path -> path.getFileName().toString()).toList())
            .containsExactlyInAnyOrder(".locks", results.getFirst().graphKey());
      }
    }
  }

  @Test
  void wrongDatasetHashFailsWithoutPublishingOrLeavingStaging() throws Exception {
    assertThatThrownBy(
            () -> RoutingTools.prepareGraph("routing/synthetic-corrupt-manifest.json", root))
        .isInstanceOf(java.io.UncheckedIOException.class);
    try (var children = Files.list(root)) {
      assertThat(children.map(path -> path.getFileName().toString()).toList())
          .containsExactly(".locks");
    }
  }

  @Test
  void refusesToReuseAnIncompleteFinalDirectory() throws Exception {
    RoutingGraphSpec spec = RoutingGraphSpec.loadManifest(MANIFEST);
    Files.createDirectory(root.resolve(spec.graphKey()));
    assertThatThrownBy(() -> RoutingTools.prepareGraph(MANIFEST, root))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("incompleto");
    assertThat(Files.isDirectory(root.resolve(spec.graphKey()))).isTrue();
  }

  @Test
  void failedWarmupNeverPublishesTheGraphAndRemovesItsStaging() throws Exception {
    assertThatThrownBy(
            () -> RoutingTools.prepareGraph("routing/synthetic-unreachable-manifest.json", root))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("aquecimento");
    try (var children = Files.list(root)) {
      assertThat(children.map(path -> path.getFileName().toString()).toList())
          .containsExactly(".locks");
    }
  }
}
