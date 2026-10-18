package dev.bora.routing;

import dev.bora.routing.internal.RoutingToolRunner;
import java.nio.file.Path;
import java.util.OptionalInt;

/** Ferramentas de roteamento executadas antes de inicializar Spring ou acessar o banco. */
public final class RoutingTools {
  private RoutingTools() {}

  public static OptionalInt run(String[] args) {
    return RoutingToolRunner.run(args, System.out, System.err);
  }

  public static GraphPreparation prepareGraph(String manifestResource, Path graphRoot) {
    return RoutingToolRunner.prepareGraph(manifestResource, graphRoot);
  }

  public record GraphPreparation(
      String graphKey, boolean reused, long importMillis, long loadMillis, long graphBytes) {}
}
