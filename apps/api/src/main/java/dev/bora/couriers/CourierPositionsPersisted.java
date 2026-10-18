package dev.bora.couriers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CourierPositionsPersisted(
    Map<UUID, List<PositionSample>> positions, Map<UUID, Long> epochs) {
  public CourierPositionsPersisted {
    Map<UUID, List<PositionSample>> copy = new LinkedHashMap<>();
    positions.forEach((id, samples) -> copy.put(id, List.copyOf(samples)));
    positions = Map.copyOf(copy);
    epochs = Map.copyOf(epochs);
    if (!positions.keySet().equals(epochs.keySet())) {
      throw new IllegalArgumentException(
          "Cada lote de posições precisa informar sua época de disponibilidade.");
    }
  }
}
