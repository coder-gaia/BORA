package dev.bora.fulfillment.internal;

import dev.bora.fulfillment.AssignedOrderView;
import dev.bora.fulfillment.CourierRouteView;
import dev.bora.fulfillment.CourierWorkView;
import dev.bora.identity.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FulfillmentController {
  private final CurrentUser currentUser;
  private final FulfillmentCoordinator fulfillment;

  FulfillmentController(CurrentUser currentUser, FulfillmentCoordinator fulfillment) {
    this.currentUser = currentUser;
    this.fulfillment = fulfillment;
  }

  @GetMapping("/api/v1/courier/work")
  @Operation(operationId = "getCourierWork", summary = "Consultar o trabalho do entregador")
  CourierWorkView work() {
    return fulfillment.work(currentUser.require());
  }

  @GetMapping("/api/v1/courier/route")
  @Operation(operationId = "getCourierRoute", summary = "Consultar a rota até a próxima etapa")
  CourierRouteView route() {
    return fulfillment.route(currentUser.require());
  }

  @PostMapping("/api/v1/orders/{id}/pickup")
  @Operation(operationId = "pickUpOrder", summary = "Confirmar a coleta na loja")
  AssignedOrderView pickUp(@PathVariable UUID id, @Valid @RequestBody PickUpRequest request) {
    return fulfillment.pickUp(currentUser.require(), id, request.expectedVersion());
  }

  @PostMapping("/api/v1/orders/{id}/deliver")
  @Operation(operationId = "deliverOrder", summary = "Confirmar a entrega ao cliente")
  AssignedOrderView deliver(@PathVariable UUID id, @Valid @RequestBody DeliverRequest request) {
    return fulfillment.deliver(
        currentUser.require(), id, request.expectedVersion(), request.confirmationCode());
  }
}
