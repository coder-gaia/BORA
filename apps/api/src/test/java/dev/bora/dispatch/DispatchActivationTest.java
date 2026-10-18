package dev.bora.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DispatchActivationTest {
  private static final String VERSION = "a".repeat(40);
  @TempDir Path directory;

  @Test
  void developmentRequiresAnExplicitBypass() {
    assertThat(new DispatchActivation(false, "", "", "").allowsCreation()).isTrue();
    assertThat(new DispatchActivation(true, "", "", "").allowsCreation()).isFalse();
  }

  @Test
  void onlyTheActiveReleaseWithBothUpstreamsCanCreateOffers() throws Exception {
    Path activation = directory.resolve("dispatch-version");
    Path mode = directory.resolve("upstream-mode");
    DispatchActivation gate = gate(activation, mode);
    assertThat(gate.allowsCreation()).isFalse();
    Files.writeString(activation, VERSION + "\n");
    assertThat(gate.allowsCreation()).isFalse();
    for (String value : new String[] {"api1", "api2", "", "BOTH", "unknown"}) {
      Files.writeString(mode, value);
      assertThat(gate.allowsCreation()).as(value).isFalse();
    }
    Files.writeString(mode, "both\n");
    assertThat(gate.allowsCreation()).isTrue();
    Files.writeString(activation, "b".repeat(40));
    assertThat(gate.allowsCreation()).isFalse();
    Files.writeString(activation, "disabled\n");
    assertThat(gate.allowsCreation()).isFalse();
  }

  @Test
  void readsReplacedFilesInsteadOfKeepingAStaleHandle() throws Exception {
    Path activation = directory.resolve("dispatch-version");
    Path mode = directory.resolve("upstream-mode");
    Files.writeString(activation, "disabled");
    Files.writeString(mode, "both");
    DispatchActivation gate = gate(activation, mode);
    assertThat(gate.allowsCreation()).isFalse();
    Path replacement = directory.resolve("replacement");
    Files.writeString(replacement, VERSION);
    Files.move(replacement, activation, StandardCopyOption.REPLACE_EXISTING);
    assertThat(gate.allowsCreation()).isTrue();
    Files.delete(activation);
    assertThat(gate.allowsCreation()).isFalse();
  }

  @Test
  void malformedPathsAndReleaseIdentifiersRemainClosed() {
    assertThat(new DispatchActivation(true, VERSION, "\u0000", "anything").allowsCreation())
        .isFalse();
    assertThat(new DispatchActivation(true, "latest", "anything", "anything").allowsCreation())
        .isFalse();
  }

  private DispatchActivation gate(Path activation, Path mode) {
    return new DispatchActivation(true, VERSION, activation.toString(), mode.toString());
  }
}
