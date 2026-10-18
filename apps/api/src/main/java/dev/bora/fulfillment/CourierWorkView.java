package dev.bora.fulfillment;

import dev.bora.couriers.CourierView;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"courier"})
public record CourierWorkView(CourierView courier, AssignedOrderView order) {}
