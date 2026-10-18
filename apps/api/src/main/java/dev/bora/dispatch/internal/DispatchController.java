package dev.bora.dispatch.internal;

import dev.bora.dispatch.CourierOfferInbox;
import dev.bora.dispatch.DispatchCoordinator;
import dev.bora.identity.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class DispatchController {
  private final DispatchCoordinator dispatch;
  private final CurrentUser currentUser;

  DispatchController(DispatchCoordinator dispatch, CurrentUser currentUser) {
    this.dispatch = dispatch;
    this.currentUser = currentUser;
  }

  @GetMapping("/courier/offer")
  @Operation(operationId = "getCourierOffer", summary = "Consultar a oferta atual do entregador")
  CourierOfferInbox inbox() {
    return dispatch.inbox(currentUser.require());
  }

  @PostMapping("/offers/{id}/accept")
  @Operation(operationId = "acceptOffer", summary = "Aceitar uma oferta dentro do prazo")
  @ApiResponse(responseCode = "204", description = "Ação concluída.")
  ResponseEntity<Void> accept(@PathVariable UUID id) {
    dispatch.accept(currentUser.require(), id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/offers/{id}/reject")
  @Operation(operationId = "rejectOffer", summary = "Recusar uma oferta dentro do prazo")
  @ApiResponse(responseCode = "204", description = "Ação concluída.")
  ResponseEntity<Void> reject(@PathVariable UUID id) {
    dispatch.reject(currentUser.require(), id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/ops/orders/{id}/reassign")
  @Operation(operationId = "reassignOrder", summary = "Redistribuir um pedido da operação")
  @ApiResponse(responseCode = "204", description = "Ação concluída.")
  ResponseEntity<Void> reassign(
      @PathVariable UUID id, @Valid @RequestBody ReassignOrderRequest body) {
    dispatch.reassign(currentUser.require(), id, body.expectedVersion());
    return ResponseEntity.noContent().build();
  }
}
