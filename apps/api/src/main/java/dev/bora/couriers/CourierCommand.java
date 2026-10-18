package dev.bora.couriers;

public sealed interface CourierCommand {

  record GoOnline() implements CourierCommand {}

  record GoOffline() implements CourierCommand {}

  record ReceiveOffer() implements CourierCommand {}

  record AcceptOffer() implements CourierCommand {}

  record RejectOffer() implements CourierCommand {}

  record ExpireOffer() implements CourierCommand {}

  record CancelOffer() implements CourierCommand {}

  record CompleteDelivery() implements CourierCommand {}

  record CancelOrder() implements CourierCommand {}

  record ReassignOrder() implements CourierCommand {}
}
