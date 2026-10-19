package dev.bora.realtime;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(requiredProperties = {"serverTime", "points"})
public record MapFrame(Instant serverTime, List<List<Object>> points) {
  public MapFrame {
    points = List.copyOf(points);
  }
}
