package dev.bora.orders;

import dev.bora.identity.DemoPrincipal;
import dev.bora.routing.GeoPoint;
import dev.bora.stores.StoreView;
import java.util.UUID;

/** Intenção privada de treino. O chamador reserva a oferta na mesma transação do pedido. */
public interface TrainingOrders {
  TrainingOrderReceipt replayTraining(DemoPrincipal user, String key);

  /** Adquire o lock do contexto antes de conferir a repetição e o limite de pedidos ativos. */
  TrainingOrderReceipt lockAndReplayTraining(DemoPrincipal user, String key);

  /** Encerra somente um treino do criador nas etapas permitidas pela máquina de estados. */
  TrainingOrderState cancelTraining(DemoPrincipal user, UUID orderId, long expectedVersion);

  /** Exige transação e contexto já travado. Nunca persiste o código de confirmação em claro. */
  TrainingOrderReceipt createTraining(
      DemoPrincipal user, String key, StoreView store, GeoPoint dropoff, UUID offerId);
}
