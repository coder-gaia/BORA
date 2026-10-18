package dev.bora.dispatch;

public sealed interface OfferCommand {

  record Accept() implements OfferCommand {}

  record Reject() implements OfferCommand {}

  record Expire() implements OfferCommand {}

  record Cancel() implements OfferCommand {}
}
