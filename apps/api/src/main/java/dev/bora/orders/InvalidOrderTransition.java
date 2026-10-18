package dev.bora.orders;

public final class InvalidOrderTransition extends RuntimeException {

  public InvalidOrderTransition(String message) {
    super(message);
  }
}
