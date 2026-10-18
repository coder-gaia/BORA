package dev.bora.identity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"authenticated", "user"})
public record SessionView(boolean authenticated, @Schema(nullable = true) DemoUserView user) {

  public static SessionView anonymous() {
    return new SessionView(false, null);
  }
}
