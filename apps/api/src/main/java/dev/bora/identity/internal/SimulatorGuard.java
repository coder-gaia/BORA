package dev.bora.identity.internal;

import dev.bora.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** A chave concede apenas a entrada nas identidades fixas do simulador interno. */
@Component
class SimulatorGuard {
  static final String HEADER = "X-Bora-Simulator-Key";
  private final byte[] expected;
  private final boolean enabled;

  SimulatorGuard(@Value("${bora.simulator.key:}") String key) {
    enabled =
        key != null
            && key.length() >= 32
            && key.length() <= 256
            && key.chars().noneMatch(Character::isWhitespace);
    expected = digest(key == null ? "" : key);
  }

  void require(String supplied) {
    boolean matches = MessageDigest.isEqual(expected, digest(supplied == null ? "" : supplied));
    if (!enabled || supplied == null || !matches) {
      throw new ApiException(
          HttpStatus.FORBIDDEN,
          "SIMULATOR_FORBIDDEN",
          "Acesso interno ao simulador não autorizado.");
    }
  }

  private static byte[] digest(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 não está disponível.", failure);
    }
  }
}
