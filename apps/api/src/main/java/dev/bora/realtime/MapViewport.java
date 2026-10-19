package dev.bora.realtime;

import dev.bora.shared.ApiException;
import org.springframework.http.HttpStatus;

public record MapViewport(double west, double south, double east, double north, Scope scope) {
  public enum Scope {
    PUBLIC,
    OPERATIONS
  }

  public MapViewport {
    if (!Double.isFinite(west)
        || !Double.isFinite(south)
        || !Double.isFinite(east)
        || !Double.isFinite(north)
        || west < -180
        || east > 180
        || south < -90
        || north > 90
        || west >= east
        || south >= north
        || scope == null) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_VIEWPORT", "Informe uma área visível válida.");
    }
  }
}
