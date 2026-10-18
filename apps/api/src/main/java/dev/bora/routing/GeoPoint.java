package dev.bora.routing;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"latitude", "longitude"})
public record GeoPoint(
    @JsonProperty(required = true) double latitude,
    @JsonProperty(required = true) double longitude) {
  public GeoPoint {
    if (!Double.isFinite(latitude)
        || !Double.isFinite(longitude)
        || latitude < -90
        || latitude > 90
        || longitude < -180
        || longitude > 180) {
      throw new IllegalArgumentException("Coordenadas geográficas inválidas.");
    }
  }
}
