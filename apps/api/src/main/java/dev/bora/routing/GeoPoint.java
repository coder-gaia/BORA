package dev.bora.routing;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"latitude", "longitude"})
public record GeoPoint(double latitude, double longitude) {
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
