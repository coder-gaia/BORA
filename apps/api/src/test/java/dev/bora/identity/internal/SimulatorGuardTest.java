package dev.bora.identity.internal;

import static org.assertj.core.api.Assertions.*;

import dev.bora.shared.ApiException;
import org.junit.jupiter.api.Test;

class SimulatorGuardTest {
  @Test
  void defaultsToClosedAndNeverAcceptsEmptyOrInvalidConfiguredSecret() {
    for (String configured : new String[] {"", "short", "with spaces".repeat(5)}) {
      SimulatorGuard guard = new SimulatorGuard(configured);
      assertThatThrownBy(() -> guard.require(configured)).isInstanceOf(ApiException.class);
      assertThatThrownBy(() -> guard.require(null)).isInstanceOf(ApiException.class);
    }
  }

  @Test
  void acceptsOnlyExactDedicatedKey() {
    String key = "test-only-key-0123456789abcdef0123456789abcdef";
    SimulatorGuard guard = new SimulatorGuard(key);
    assertThatCode(() -> guard.require(key)).doesNotThrowAnyException();
    assertThatThrownBy(() -> guard.require(key.substring(1))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> guard.require(key + "x")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> guard.require("x".repeat(key.length())))
        .isInstanceOf(ApiException.class);
  }
}
