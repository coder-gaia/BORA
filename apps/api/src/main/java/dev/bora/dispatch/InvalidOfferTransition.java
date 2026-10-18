package dev.bora.dispatch;

public final class InvalidOfferTransition extends RuntimeException {

  public InvalidOfferTransition(String message) {
    super(message);
  }
}
