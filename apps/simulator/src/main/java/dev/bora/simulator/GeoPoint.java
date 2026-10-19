package dev.bora.simulator;

record GeoPoint(double latitude, double longitude) {
  GeoPoint {
    if (!Double.isFinite(latitude)
        || !Double.isFinite(longitude)
        || latitude < -90
        || latitude > 90
        || longitude < -180
        || longitude > 180) {
      throw new IllegalArgumentException("Posição inválida.");
    }
  }

  double distanceTo(GeoPoint other) {
    double latitudeDelta = Math.toRadians(other.latitude - latitude);
    double longitudeDelta = Math.toRadians(other.longitude - longitude);
    double a =
        Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
            + Math.cos(Math.toRadians(latitude))
                * Math.cos(Math.toRadians(other.latitude))
                * Math.sin(longitudeDelta / 2)
                * Math.sin(longitudeDelta / 2);
    return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
  }

  GeoPoint interpolate(GeoPoint other, double fraction) {
    return new GeoPoint(
        latitude + (other.latitude - latitude) * fraction,
        longitude + (other.longitude - longitude) * fraction);
  }
}
