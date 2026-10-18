package dev.bora.couriers;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(requiredProperties = {"accepted", "ignored", "latestRecordedAt", "flushDeadline"})
public record PositionReceipt(
    int accepted, int ignored, Instant latestRecordedAt, Instant flushDeadline) {}
