package dev.bora.couriers.internal;

import dev.bora.couriers.CourierView;
import dev.bora.couriers.PositionReceipt;
import dev.bora.identity.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/courier")
class CouriersController {
  private final CurrentUser user;
  private final CourierIngestionService couriers;

  CouriersController(CurrentUser user, CourierIngestionService couriers) {
    this.user = user;
    this.couriers = couriers;
  }

  @GetMapping("/me")
  @Operation(
      operationId = "getCurrentCourier",
      summary = "Consultar o entregador e sua posição gravada")
  CourierView me() {
    return couriers.me(user.require());
  }

  @PostMapping("/availability")
  @Operation(operationId = "setCourierAvailability", summary = "Ficar online ou offline")
  CourierView availability(@Valid @RequestBody AvailabilityRequest request) {
    return couriers.availability(user.require(), request);
  }

  @PostMapping("/positions")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(
      operationId = "submitCourierPositions",
      summary = "Receber um lote de posições",
      description =
          "O recibo confirma o recebimento em memória. Consulte a posição gravada após o prazo de gravação.")
  PositionReceipt positions(@Valid @RequestBody PositionBatchRequest request) {
    return couriers.positions(user.require(), request.positions());
  }
}
