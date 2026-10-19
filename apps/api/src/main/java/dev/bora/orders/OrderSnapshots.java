package dev.bora.orders;

import dev.bora.identity.DemoPrincipal;
import java.util.UUID;

/** Leitura autorizada compartilhada pelo HTTP e pelo tempo real. */
public interface OrderSnapshots {
  OrderView snapshot(DemoPrincipal principal, UUID orderId);
}
