package dev.bora.dispatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bloqueia novas reservas durante uma atualização ou recuperação parcial. */
@Component
public class DispatchActivation {
  private final boolean required;
  private final String releaseVersion;
  private final String activationFile;
  private final String upstreamModeFile;

  public DispatchActivation(
      @Value("${bora.dispatch.require-activation:true}") boolean required,
      @Value("${bora.dispatch.release-version:}") String releaseVersion,
      @Value("${bora.dispatch.activation-file:}") String activationFile,
      @Value("${bora.dispatch.upstream-mode-file:}") String upstreamModeFile) {
    this.required = required;
    this.releaseVersion = releaseVersion;
    this.activationFile = activationFile;
    this.upstreamModeFile = upstreamModeFile;
  }

  public boolean allowsCreation() {
    if (!required) return true;
    if (releaseVersion == null || !releaseVersion.matches("[0-9a-f]{40}")) return false;
    if (activationFile == null
        || activationFile.isBlank()
        || upstreamModeFile == null
        || upstreamModeFile.isBlank()) return false;
    try {
      return Files.readString(Path.of(activationFile)).strip().equals(releaseVersion)
          && Files.readString(Path.of(upstreamModeFile)).strip().equals("both");
    } catch (IOException | InvalidPathException | SecurityException failure) {
      return false;
    }
  }
}
