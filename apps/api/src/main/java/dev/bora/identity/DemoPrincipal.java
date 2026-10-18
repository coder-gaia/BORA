package dev.bora.identity;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

public record DemoPrincipal(
    UUID userId, UUID contextId, DemoRole role, UUID storeId, UUID courierId, boolean visitor)
    implements Serializable, Principal {

  @Serial private static final long serialVersionUID = 1L;

  public DemoPrincipal {
    Objects.requireNonNull(userId, "Informe o usuário.");
    Objects.requireNonNull(contextId, "Informe o contexto da demonstração.");
    Objects.requireNonNull(role, "Informe o papel.");
    if ((role == DemoRole.STORE) != (storeId != null)
        || (role == DemoRole.COURIER) != (courierId != null)) {
      throw new IllegalArgumentException("O vínculo do usuário não corresponde ao papel.");
    }
  }

  @Override
  public String getName() {
    return userId.toString();
  }
}
