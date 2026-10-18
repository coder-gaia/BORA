package dev.bora.dispatch;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(requiredProperties = {"offer", "serverTime"})
public record CourierOfferInbox(
    @Schema(nullable = true, description = "Oferta pendente dentro do prazo, ou null.")
        CourierOffer offer,
    Instant serverTime) {}
