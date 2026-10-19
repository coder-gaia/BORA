package dev.bora.realtime.internal;

import dev.bora.identity.CurrentUser;
import dev.bora.realtime.MapFrame;
import dev.bora.realtime.MapViewport;
import dev.bora.realtime.SimulationConfig;
import dev.bora.realtime.TrackingSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class RealtimeController {
  private final RealtimeReadModel model;
  private final CurrentUser current;
  private final SimulationConfig simulation;

  RealtimeController(
      RealtimeReadModel model,
      CurrentUser current,
      @Value("${bora.routing.simulation-speed:3}") double simulationSpeed) {
    this.model = model;
    this.current = current;
    this.simulation = new SimulationConfig(simulationSpeed);
  }

  @GetMapping("/map/config")
  @Operation(operationId = "getMapConfig", summary = "Consultar o fator de velocidade da simulação")
  SimulationConfig config() {
    return simulation;
  }

  @GetMapping("/map/snapshot")
  @Operation(
      operationId = "getPublicMapSnapshot",
      summary = "Consultar posições simuladas na área visível")
  MapFrame publicMap(
      @RequestParam double west,
      @RequestParam double south,
      @RequestParam double east,
      @RequestParam double north) {
    return model.map(new MapViewport(west, south, east, north, MapViewport.Scope.PUBLIC), null);
  }

  @GetMapping("/ops/snapshot")
  @Operation(
      operationId = "getOperationsSnapshot",
      summary = "Consultar posições públicas e do próprio contexto")
  MapFrame operations(
      @RequestParam double west,
      @RequestParam double south,
      @RequestParam double east,
      @RequestParam double north) {
    return model.map(
        new MapViewport(west, south, east, north, MapViewport.Scope.OPERATIONS), current.require());
  }

  @GetMapping("/tracking/{code}")
  @Operation(
      operationId = "getTracking",
      summary = "Consultar o progresso limitado de um link de rastreio")
  TrackingSnapshot tracking(@PathVariable String code) {
    return model.tracking(code);
  }
}
