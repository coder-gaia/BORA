package dev.bora.orders;

import java.util.UUID;

public final class OrderVersionConflict extends RuntimeException {

  public OrderVersionConflict(UUID orderId) {
    super("O pedido " + orderId + " mudou. Atualize os dados antes de tentar novamente.");
  }

  public OrderVersionConflict(UUID orderId, Throwable cause) {
    super("O pedido " + orderId + " mudou. Atualize os dados antes de tentar novamente.", cause);
  }
}
