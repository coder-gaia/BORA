package dev.bora.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStateTest {

  private static final UUID COURIER = UUID.fromString("72b69d43-68fa-4c90-91db-28ca7652d4eb");
  private static final UUID NEXT_COURIER = UUID.fromString("24df739a-fb91-4efa-b382-33fe0174fdd0");
  private static final Instant OCCURRED_AT = Instant.parse("2026-10-18T13:00:00Z");
  private static final List<OrderCommand> COMMANDS =
      List.of(
          new OrderCommand.Assign(NEXT_COURIER),
          new OrderCommand.ArriveAtStore(),
          new OrderCommand.PickUp(),
          new OrderCommand.Deliver(),
          new OrderCommand.CancelByStore(),
          new OrderCommand.CancelByOperations(),
          new OrderCommand.Reassign());

  @ParameterizedTest(name = "{0} + {1} => {2}")
  @MethodSource("transitions")
  void enforcesEveryStateAndCommand(
      OrderStatus status, OrderCommand command, OrderStatus expectedStatus) {
    UUID originalCourier = status == OrderStatus.SEARCHING ? null : COURIER;
    OrderState state = OrderState.restore(status, originalCourier);

    if (expectedStatus == null) {
      assertThatThrownBy(() -> state.apply(command, OCCURRED_AT))
          .isInstanceOf(InvalidOrderTransition.class);
    } else {
      OrderTransition transition = state.apply(command, OCCURRED_AT);
      UUID expectedCourier =
          expectedStatus == OrderStatus.SEARCHING
              ? null
              : command instanceof OrderCommand.Assign ? NEXT_COURIER : originalCourier;

      assertThat(transition.previous()).isSameAs(state);
      assertThat(transition.next().status()).isEqualTo(expectedStatus);
      assertThat(transition.next().courierId()).isEqualTo(expectedCourier);
      assertThat(transition.command()).isSameAs(command);
      assertThat(transition.occurredAt()).isEqualTo(OCCURRED_AT);
    }

    assertThat(state.status()).isEqualTo(status);
    assertThat(state.courierId()).isEqualTo(originalCourier);
  }

  static Stream<Arguments> transitions() {
    // Colunas: atribuir, chegar, coletar, entregar, cancelar loja, cancelar operação, redistribuir.
    return Stream.of(
            "SEARCHING ASSIGNED - - - CANCELED CANCELED -",
            "ASSIGNED - AT_STORE - - CANCELED CANCELED SEARCHING",
            "AT_STORE - - PICKED_UP - - CANCELED SEARCHING",
            "PICKED_UP - - - DELIVERED - - -",
            "DELIVERED - - - - - - -",
            "CANCELED - - - - - - -")
        .flatMap(
            row -> {
              String[] cells = row.split(" ");
              return java.util.stream.IntStream.range(0, COMMANDS.size())
                  .mapToObj(
                      index ->
                          Arguments.of(
                              OrderStatus.valueOf(cells[0]),
                              COMMANDS.get(index),
                              cells[index + 1].equals("-")
                                  ? null
                                  : OrderStatus.valueOf(cells[index + 1])));
            });
  }

  @ParameterizedTest
  @CsvSource({
    "SEARCHING, false, true",
    "SEARCHING, true, false",
    "ASSIGNED, false, false",
    "ASSIGNED, true, true",
    "AT_STORE, false, false",
    "AT_STORE, true, true",
    "PICKED_UP, false, false",
    "PICKED_UP, true, true",
    "DELIVERED, false, false",
    "DELIVERED, true, true",
    "CANCELED, false, true",
    "CANCELED, true, true"
  })
  void restoresOnlyConsistentCourierAssignments(
      OrderStatus status, boolean hasCourier, boolean valid) {
    UUID courier = hasCourier ? COURIER : null;

    if (valid) {
      OrderState state = OrderState.restore(status, courier);
      assertThat(state.status()).isEqualTo(status);
      assertThat(state.courierId()).isEqualTo(courier);
    } else {
      assertThatThrownBy(() -> OrderState.restore(status, courier))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void permitsAnotherCourierAfterReassignment() {
    OrderState state = new OrderState.Searching();
    state = state.apply(new OrderCommand.Assign(COURIER), OCCURRED_AT).next();
    state = state.apply(new OrderCommand.ArriveAtStore(), OCCURRED_AT.plusSeconds(60)).next();
    state = state.apply(new OrderCommand.Reassign(), OCCURRED_AT.plusSeconds(70)).next();

    assertThat(state).isEqualTo(new OrderState.Searching());

    state = state.apply(new OrderCommand.Assign(NEXT_COURIER), OCCURRED_AT.plusSeconds(80)).next();
    state = state.apply(new OrderCommand.ArriveAtStore(), OCCURRED_AT.plusSeconds(140)).next();
    state = state.apply(new OrderCommand.PickUp(), OCCURRED_AT.plusSeconds(160)).next();
    state = state.apply(new OrderCommand.Deliver(), OCCURRED_AT.plusSeconds(500)).next();

    assertThat(state).isEqualTo(new OrderState.Delivered(NEXT_COURIER));
  }
}
