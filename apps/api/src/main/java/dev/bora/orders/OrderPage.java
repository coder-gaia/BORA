package dev.bora.orders;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"items", "page", "size", "totalElements", "totalPages"})
public record OrderPage(
    List<OrderSummary> items, int page, int size, long totalElements, int totalPages) {
  public OrderPage {
    items = List.copyOf(items);
  }
}
