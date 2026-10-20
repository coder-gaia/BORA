package dev.bora.orders;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(requiredProperties = {"items", "page", "size", "totalElements", "totalPages", "snapshotAt"})
public record OrderPage(
    List<OrderSummary> items,
    int page,
    int size,
    long totalElements,
    int totalPages,
    Instant snapshotAt) {
  public OrderPage {
    items = List.copyOf(items);
  }
}
