package dev.bora.orders.internal;

import dev.bora.identity.CurrentUser;
import dev.bora.orders.OrderPage;
import dev.bora.orders.OrderScope;
import dev.bora.orders.OrderSnapshots;
import dev.bora.orders.OrderSort;
import dev.bora.orders.OrderStatus;
import dev.bora.orders.OrderView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
class OrdersController {
  private final CurrentUser currentUser;
  private final OrdersService orders;
  private final OrderSnapshots snapshots;

  OrdersController(CurrentUser currentUser, OrdersService orders, OrderSnapshots snapshots) {
    this.currentUser = currentUser;
    this.orders = orders;
    this.snapshots = snapshots;
  }

  @PostMapping
  @Operation(
      operationId = "createOrder",
      summary = "Criar um pedido de entrega",
      description =
          "Cria um pedido da loja autenticada. A mesma chave e o mesmo destino repetem a resposta original.")
  @ApiResponse(
      responseCode = "201",
      description = "Pedido criado ou repetido pela chave de idempotência.",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = OrderView.class)))
  ResponseEntity<OrderView> create(
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CreateOrderRequest request) {
    OrderView order = orders.create(currentUser.require(), key, request);
    return ResponseEntity.created(URI.create("/api/v1/orders/" + order.id())).body(order);
  }

  @GetMapping
  @Operation(
      operationId = "listOrders",
      summary = "Listar pedidos visíveis",
      description =
          "Filtra todo o conjunto autorizado por estado, código público exato e escopo. "
              + "Reutilize snapshotAt nas próximas páginas para excluir novas criações; "
              + "os estados continuam atuais. OWN mostra o próprio contexto, PUBLIC o cenário "
              + "simulado e ALL combina ambos. WAITING é exclusivo da operação com status SEARCHING "
              + "e ordena pelo início da tentativa atual de busca; size permanece entre 1 e 100.")
  OrderPage list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(required = false) String code,
      @RequestParam(defaultValue = "ALL") OrderScope scope,
      @RequestParam(required = false) Instant snapshotAt,
      @RequestParam(defaultValue = "RECENT") OrderSort sort) {
    return orders.list(currentUser.require(), page, size, status, code, scope, snapshotAt, sort);
  }

  @GetMapping("/{id}")
  @Operation(operationId = "getOrder", summary = "Consultar um pedido e sua linha do tempo")
  OrderView detail(@PathVariable UUID id) {
    return snapshots.snapshot(currentUser.require(), id);
  }

  @PostMapping("/{id}/cancel")
  @Operation(
      operationId = "cancelOrder",
      summary = "Cancelar um pedido",
      description = "Exige a versão atual e respeita o papel, a propriedade e a etapa do pedido.")
  OrderView cancel(@PathVariable UUID id, @Valid @RequestBody CancelOrderRequest request) {
    return orders.cancel(currentUser.require(), id, request.expectedVersion());
  }
}
