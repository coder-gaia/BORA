package dev.bora.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OfferStateTest {

  private static final Instant EXPIRES_AT = Instant.parse("2026-10-18T13:00:30Z");
  private static final List<OfferCommand> COMMANDS =
      List.of(
          new OfferCommand.Accept(),
          new OfferCommand.Reject(),
          new OfferCommand.Expire(),
          new OfferCommand.Cancel());

  @ParameterizedTest(name = "{0} + {1} em {2} => {3}")
  @MethodSource("transitions")
  void enforcesEveryStateCommandAndDeadlineBoundary(
      OfferStatus status, OfferCommand command, Instant occurredAt, OfferStatus expectedStatus) {
    OfferState state = OfferState.restore(status, EXPIRES_AT);

    if (expectedStatus == null) {
      assertThatThrownBy(() -> state.apply(command, occurredAt))
          .isInstanceOf(InvalidOfferTransition.class);
    } else {
      OfferTransition transition = state.apply(command, occurredAt);

      assertThat(transition.previous()).isSameAs(state);
      assertThat(transition.next().status()).isEqualTo(expectedStatus);
      assertThat(transition.next().expiresAt()).isEqualTo(EXPIRES_AT);
      assertThat(transition.command()).isSameAs(command);
      assertThat(transition.occurredAt()).isEqualTo(occurredAt);
    }

    assertThat(state.status()).isEqualTo(status);
    assertThat(state.expiresAt()).isEqualTo(EXPIRES_AT);
  }

  static Stream<Arguments> transitions() {
    // Colunas: aceitar, recusar, expirar, cancelar; limites em nanossegundos, sem relógio real.
    return Stream.of(
            "PENDING -1 ACCEPTED REJECTED - CANCELED",
            "PENDING 0 - - EXPIRED CANCELED",
            "PENDING 1 - - EXPIRED CANCELED",
            "ACCEPTED -1 - - - -",
            "ACCEPTED 0 - - - -",
            "ACCEPTED 1 - - - -",
            "REJECTED -1 - - - -",
            "REJECTED 0 - - - -",
            "REJECTED 1 - - - -",
            "EXPIRED -1 - - - -",
            "EXPIRED 0 - - - -",
            "EXPIRED 1 - - - -",
            "CANCELED -1 - - - -",
            "CANCELED 0 - - - -",
            "CANCELED 1 - - - -")
        .flatMap(
            row -> {
              String[] cells = row.split(" ");
              return IntStream.range(0, COMMANDS.size())
                  .mapToObj(
                      index ->
                          Arguments.of(
                              OfferStatus.valueOf(cells[0]),
                              COMMANDS.get(index),
                              EXPIRES_AT.plusNanos(Long.parseLong(cells[1])),
                              cells[index + 2].equals("-")
                                  ? null
                                  : OfferStatus.valueOf(cells[index + 2])));
            });
  }

  @Test
  void cannotAcceptAnExpiredOfferAfterRestoringItsPersistedState() {
    OfferState pending = OfferState.restore(OfferStatus.PENDING, EXPIRES_AT);
    Instant afterRestart = EXPIRES_AT.plusSeconds(600);

    assertThatThrownBy(() -> pending.apply(new OfferCommand.Accept(), afterRestart))
        .isInstanceOf(InvalidOfferTransition.class);
    assertThat(pending.apply(new OfferCommand.Expire(), afterRestart).next())
        .isEqualTo(new OfferState.Expired(EXPIRES_AT));
  }
}
