package dev.bora.identity.internal;

import dev.bora.identity.CurrentUser;
import dev.bora.identity.DemoRole;
import dev.bora.routing.GeoPoint;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingService;
import dev.bora.shared.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulator")
class SimulatorController {
  private final SimulatorGuard guard;
  private final SimulatorDirectory directory;
  private final RoutingService routing;
  private final CurrentUser users;

  SimulatorController(
      SimulatorGuard guard,
      SimulatorDirectory directory,
      RoutingService routing,
      CurrentUser users) {
    this.guard = guard;
    this.directory = directory;
    this.routing = routing;
    this.users = users;
  }

  @GetMapping("/scenario")
  @Operation(hidden = true)
  SimulatorDirectory.ScenarioView scenario(
      @RequestHeader(value = SimulatorGuard.HEADER, required = false) String key) {
    guard.require(key);
    return directory.scenario();
  }

  @PostMapping("/route")
  @Operation(hidden = true)
  IdleRouteView route(
      @RequestHeader(value = SimulatorGuard.HEADER, required = false) String key,
      @Valid @RequestBody IdleRouteRequest request) {
    guard.require(key);
    var user = users.require();
    if (user.visitor() || user.role() != DemoRole.COURIER) {
      throw new ApiException(
          HttpStatus.FORBIDDEN,
          "SIMULATOR_FORBIDDEN",
          "A rota interna exige um entregador do simulador.");
    }
    try {
      var route = routing.route(request.origin(), request.destination());
      return new IdleRouteView(
          route.geometry(),
          route.distanceMeters(),
          route.duration().toMillis(),
          route.simulatedDuration().toMillis());
    } catch (RoutingException failure) {
      throw new ApiException(
          failure.reason() == RoutingException.Reason.GRAPH_UNAVAILABLE
              ? HttpStatus.SERVICE_UNAVAILABLE
              : HttpStatus.UNPROCESSABLE_ENTITY,
          "SIMULATOR_ROUTE_UNAVAILABLE",
          "Não encontramos uma rota para o simulador.");
    }
  }

  record IdleRouteRequest(@NotNull @Valid GeoPoint origin, @NotNull @Valid GeoPoint destination) {}

  record IdleRouteView(
      List<GeoPoint> geometry,
      double distanceMeters,
      long durationMillis,
      long simulatedDurationMillis) {}
}
