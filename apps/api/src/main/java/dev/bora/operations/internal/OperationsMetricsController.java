package dev.bora.operations.internal;

import dev.bora.identity.CurrentUser;
import dev.bora.operations.OperationsMetrics;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperationsMetricsController {
  private final CurrentUser current;
  private final OperationsMetricsService metrics;

  OperationsMetricsController(CurrentUser current, OperationsMetricsService metrics) {
    this.current = current;
    this.metrics = metrics;
  }

  @GetMapping(value = "/api/v1/ops/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getOperationsMetrics",
      summary = "Consultar os indicadores da operação",
      description =
          "Conta pedidos públicos simulados e do próprio contexto numa consulta coerente. "
              + "Espera considera a tentativa atual de busca; os alertas excluem pedidos encerrados. "
              + "Os totais independem do viewport e da página ou filtros abertos na interface.")
  OperationsMetrics metrics() {
    return metrics.read(current.require());
  }
}
