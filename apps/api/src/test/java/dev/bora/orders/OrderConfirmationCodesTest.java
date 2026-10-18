package dev.bora.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderConfirmationCodesTest {
  private static final String SECRET = "test-confirmation-key-with-at-least-32-characters";

  @Test
  void derivesStableSixDigitsAndSeparatelyVerifiesTheirHash() {
    var codes = new OrderConfirmationCodes(SECRET);
    UUID id = UUID.randomUUID();
    String code = codes.code(id);
    assertThat(code).matches("[0-9]{6}");
    assertThat(codes.code(id)).isEqualTo(code);
    assertThat(codes.hash(id)).matches("[0-9a-f]{64}");
    assertThat(codes.verify(id, code, codes.hash(id))).isTrue();
    String wrong =
        String.format(java.util.Locale.ROOT, "%06d", (Integer.parseInt(code) + 1) % 1_000_000);
    assertThat(codes.verify(id, wrong, codes.hash(id))).isFalse();
    assertThat(codes.verify(UUID.randomUUID(), code, codes.hash(id))).isFalse();
    assertThat(new OrderConfirmationCodes(SECRET + "different").verify(id, code, codes.hash(id)))
        .isFalse();
  }

  @Test
  void refusesMissingMalformedCodesAndWeakKeys() {
    var codes = new OrderConfirmationCodes(SECRET);
    UUID id = UUID.randomUUID();
    assertThat(codes.verify(id, null, codes.hash(id))).isFalse();
    assertThat(codes.verify(id, "12345", codes.hash(id))).isFalse();
    assertThat(codes.verify(id, "1234567", codes.hash(id))).isFalse();
    assertThat(codes.verify(id, "12345x", codes.hash(id))).isFalse();
    assertThat(codes.verify(id, codes.code(id), null)).isFalse();
    assertThatThrownBy(() -> new OrderConfirmationCodes("short"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OrderConfirmationCodes(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
