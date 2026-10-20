package dev.bora.dispatch.internal;

import dev.bora.dispatch.TrainingOrderCoordinator;
import dev.bora.identity.CurrentUser;
import dev.bora.orders.TrainingOrderReceipt;
import dev.bora.orders.TrainingOrderState;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TrainingOrderController {
  private final TrainingOrderCoordinator training;
  private final CurrentUser current;

  TrainingOrderController(TrainingOrderCoordinator training, CurrentUser current) {
    this.training = training;
    this.current = current;
  }

  @PostMapping(
      value = "/api/v1/courier/training-order",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "createCourierTrainingOrder",
      summary = "Criar um pedido privado de treino com oferta para o próprio entregador",
      description =
          "Repetir a mesma Idempotency-Key preserva o pedido, a oferta e o prazo original. "
              + "O código de confirmação é devolvido somente ao criador nesta resposta.")
  TrainingOrderReceipt create(@RequestHeader("Idempotency-Key") String key) {
    return training.create(current.require(), key);
  }

  @PostMapping(
      value = "/api/v1/courier/training-order/{id}/cancel",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "cancelCourierTrainingOrder",
      summary = "Encerrar um treino do próprio entregador antes da coleta")
  TrainingOrderState cancel(
      @PathVariable UUID id, @Valid @RequestBody CancelTrainingOrderRequest body) {
    return training.cancel(current.require(), id, body.expectedVersion());
  }
}
