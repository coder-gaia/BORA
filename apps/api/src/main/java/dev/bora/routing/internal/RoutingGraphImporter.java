package dev.bora.routing.internal;

import com.graphhopper.GraphHopper;
import com.graphhopper.util.Constants;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

final class RoutingGraphImporter {
  private RoutingGraphImporter() {}

  static ImportResult prepare(RoutingGraphSpec spec, Path requestedRoot) {
    Path root = requestedRoot.toAbsolutePath().normalize();
    Path staging = null;
    Throwable failure = null;
    try {
      Files.createDirectories(root.resolve(".locks"));
      Path target = root.resolve(spec.graphKey());
      try (FileChannel channel =
              FileChannel.open(
                  root.resolve(".locks").resolve(spec.graphKey() + ".lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE);
          FileLock ignored = acquireLock(channel)) {
        if (Files.exists(target)) {
          if (!spec.isComplete(target)) {
            throw new IllegalStateException("O diretório definitivo do grafo está incompleto.");
          }
          long startLoad = System.nanoTime();
          GraphHopper existing = GraphHopperRoutingEngine.loadAndWarm(spec, target);
          existing.close();
          return new ImportResult(
              spec.graphKey(), true, 0, elapsed(startLoad), directoryBytes(target));
        }
        staging = Files.createTempDirectory(root, ".preparing-" + spec.graphKey() + "-");
        Path source = staging.resolve(spec.datasetFile());
        extractDataset(spec, source);
        Path graphDirectory = staging.resolve("graph");
        long startImport = System.nanoTime();
        GraphHopper importer = spec.createHopper(graphDirectory, true, source);
        try {
          importer.importOrLoad();
        } finally {
          importer.close();
        }
        long importMillis = elapsed(startImport);
        long startLoad = System.nanoTime();
        GraphHopper verification = GraphHopperRoutingEngine.loadAndWarm(spec, graphDirectory);
        verification.close();
        long loadMillis = elapsed(startLoad);
        RoutingGraphSpec.JSON.writeValue(
            graphDirectory.resolve(RoutingGraphSpec.COMPLETION_FILE).toFile(),
            Map.of(
                "graphKey", spec.graphKey(),
                "datasetSha256", spec.datasetSha256(),
                "graphHopperVersion", Constants.VERSION));
        Files.move(graphDirectory, target, StandardCopyOption.ATOMIC_MOVE);
        return new ImportResult(
            spec.graphKey(), false, importMillis, loadMillis, directoryBytes(target));
      }
    } catch (IOException exception) {
      UncheckedIOException wrapped =
          new UncheckedIOException("Não foi possível preparar o grafo de roteamento.", exception);
      failure = wrapped;
      throw wrapped;
    } catch (RuntimeException | Error exception) {
      failure = exception;
      throw exception;
    } finally {
      if (staging != null) {
        try {
          removeStaging(staging, root);
        } catch (RuntimeException cleanupFailure) {
          if (failure == null) {
            throw cleanupFailure;
          }
          failure.addSuppressed(cleanupFailure);
        }
      }
    }
  }

  private static FileLock acquireLock(FileChannel channel) throws IOException {
    long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        FileLock lock = channel.tryLock();
        if (lock != null) {
          return lock;
        }
      } catch (OverlappingFileLockException ignored) {
        // Outro importador nesta JVM mantém o lock; aguarda seu resultado imutável.
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("A espera pela preparação do grafo foi interrompida.", exception);
      }
    }
    throw new IOException("Tempo esgotado aguardando a preparação do grafo.");
  }

  private static void extractDataset(RoutingGraphSpec spec, Path target) throws IOException {
    MessageDigest digest = RoutingGraphSpec.digest();
    long bytes = 0;
    try (InputStream input = RoutingGraphSpec.openResource(spec.datasetResource());
        OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
      byte[] buffer = new byte[64 * 1024];
      int count;
      while ((count = input.read(buffer)) != -1) {
        bytes += count;
        if (bytes > spec.sizeBytes()) {
          throw new IOException("O conjunto de vias excede o tamanho declarado.");
        }
        digest.update(buffer, 0, count);
        output.write(buffer, 0, count);
      }
    }
    if (bytes != spec.sizeBytes()
        || !HexFormat.of().formatHex(digest.digest()).equals(spec.datasetSha256())) {
      throw new IOException("O SHA-256 ou o tamanho do conjunto de vias não confere.");
    }
  }

  static long directoryBytes(Path directory) throws IOException {
    try (var paths = Files.walk(directory)) {
      return paths
          .filter(Files::isRegularFile)
          .mapToLong(
              path -> {
                try {
                  return Files.size(path);
                } catch (IOException exception) {
                  throw new UncheckedIOException(exception);
                }
              })
          .sum();
    }
  }

  private static void removeStaging(Path staging, Path root) {
    Path checked = staging.toAbsolutePath().normalize();
    if (!checked.getParent().equals(root)
        || !checked.getFileName().toString().startsWith(".preparing-")) {
      throw new IllegalStateException("Diretório temporário fora da raiz de roteamento.");
    }
    try (var paths = Files.walk(checked)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Não foi possível remover o preparo temporário.", exception);
    }
  }

  private static long elapsed(long start) {
    return Duration.ofNanos(System.nanoTime() - start).toMillis();
  }

  record ImportResult(
      String graphKey, boolean reused, long importMillis, long loadMillis, long graphBytes) {}
}
