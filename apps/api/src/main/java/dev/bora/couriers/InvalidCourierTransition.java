package dev.bora.couriers;

public final class InvalidCourierTransition extends RuntimeException {

  public InvalidCourierTransition(String message) {
    super(message);
  }
}
