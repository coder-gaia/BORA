package dev.bora.orders;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** O código pertence apenas à resposta da intenção de treino criada pelo próprio visitante. */
@Schema(
    requiredProperties = {
      "orderId",
      "offerId",
      "expiresAt",
      "confirmationCode",
      "status",
      "version",
      "serverTime"
    })
public record TrainingOrderReceipt(
    UUID orderId,
    UUID offerId,
    Instant expiresAt,
    @Schema(pattern = "[0-9]{6}") String confirmationCode,
    OrderStatus status,
    long version,
    Instant serverTime) {}
