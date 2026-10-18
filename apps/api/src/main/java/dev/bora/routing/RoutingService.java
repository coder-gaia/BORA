package dev.bora.routing;

public interface RoutingService {
  Route route(GeoPoint origin, GeoPoint destination);

  SnappedPoint snap(GeoPoint point);
}
