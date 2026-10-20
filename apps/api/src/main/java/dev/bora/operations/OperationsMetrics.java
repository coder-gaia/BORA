package dev.bora.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Contagens da visão geral autorizada, independentes do mapa e da página de pedidos. */
@Schema(
    requiredProperties = {
      "activeOrders",
      "searchingOrders",
      "searchingOverThreeMinutes",
      "overdueOrders",
      "dueWithinFiveMinutes",
      "deliveredLastHour",
      "serverTime"
    })
public record OperationsMetrics(
    @Schema(minimum = "0") long activeOrders,
    @Schema(minimum = "0") long searchingOrders,
    @Schema(minimum = "0") long searchingOverThreeMinutes,
    @Schema(minimum = "0") long overdueOrders,
    @Schema(minimum = "0") long dueWithinFiveMinutes,
    @Schema(minimum = "0") long deliveredLastHour,
    Instant serverTime) {}
