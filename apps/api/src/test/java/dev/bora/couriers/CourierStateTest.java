package dev.bora.couriers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CourierStateTest {

  private static final Instant OCCURRED_AT = Instant.parse("2026-10-18T13:00:00Z");
  private static final List<CourierCommand> COMMANDS =
      List.of(
          new CourierCommand.GoOnline(),
          new CourierCommand.GoOffline(),
          new CourierCommand.ReceiveOffer(),
          new CourierCommand.AcceptOffer(),
          new CourierCommand.RejectOffer(),
          new CourierCommand.ExpireOffer(),
          new CourierCommand.CancelOffer(),
          new CourierCommand.CompleteDelivery(),
          new CourierCommand.CancelOrder(),
          new CourierCommand.ReassignOrder());

  @ParameterizedTest(name = "{0} + {1} => {2}")
  @MethodSource("transitions")
  void enforcesEveryStateAndCommand(
      CourierStatus status, CourierCommand command, CourierStatus expectedStatus) {
    CourierState state = CourierState.restore(status);

    if (expectedStatus == null) {
      assertThatThrownBy(() -> state.apply(command, OCCURRED_AT))
          .isInstanceOf(InvalidCourierTransition.class);
    } else {
      CourierTransition transition = state.apply(command, OCCURRED_AT);

      assertThat(transition.previous()).isSameAs(state);
      assertThat(transition.next().status()).isEqualTo(expectedStatus);
      assertThat(transition.command()).isSameAs(command);
      assertThat(transition.occurredAt()).isEqualTo(OCCURRED_AT);
    }

    assertThat(state.status()).isEqualTo(status);
  }

  static Stream<Arguments> transitions() {
    // Colunas seguem os comandos acima; cada linha lista também todas as proibições.
    return Stream.of(
            "OFFLINE AVAILABLE - - - - - - - - -",
            "AVAILABLE - OFFLINE OFFERED - - - - - - -",
            "OFFERED - - - BUSY AVAILABLE AVAILABLE AVAILABLE - - -",
            "BUSY - - - - - - - AVAILABLE AVAILABLE AVAILABLE")
        .flatMap(
            row -> {
              String[] cells = row.split(" ");
              return java.util.stream.IntStream.range(0, COMMANDS.size())
                  .mapToObj(
                      index ->
                          Arguments.of(
                              CourierStatus.valueOf(cells[0]),
                              COMMANDS.get(index),
                              cells[index + 1].equals("-")
                                  ? null
                                  : CourierStatus.valueOf(cells[index + 1])));
            });
  }

  @Test
  void completesDeliveryBeforeGoingOffline() {
    CourierState state = new CourierState.Offline();
    state = state.apply(new CourierCommand.GoOnline(), OCCURRED_AT).next();
    state = state.apply(new CourierCommand.ReceiveOffer(), OCCURRED_AT.plusSeconds(1)).next();
    state = state.apply(new CourierCommand.AcceptOffer(), OCCURRED_AT.plusSeconds(2)).next();

    assertThat(state).isEqualTo(new CourierState.Busy());

    state = state.apply(new CourierCommand.CompleteDelivery(), OCCURRED_AT.plusSeconds(300)).next();
    state = state.apply(new CourierCommand.GoOffline(), OCCURRED_AT.plusSeconds(301)).next();

    assertThat(state).isEqualTo(new CourierState.Offline());
  }
}
