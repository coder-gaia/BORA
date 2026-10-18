package dev.bora.routing;

public final class RoutingException extends RuntimeException {
  public enum Reason {
    GRAPH_UNAVAILABLE,
    NO_ROUTE,
    SNAP_TOO_FAR
  }

  private final Reason reason;

  public RoutingException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
