package dev.bora.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(requiredProperties = {"id", "displayName", "role", "expiresAt"})
public record DemoUserView(
    UUID id, String displayName, DemoRole role, UUID storeId, UUID courierId, Instant expiresAt) {}
