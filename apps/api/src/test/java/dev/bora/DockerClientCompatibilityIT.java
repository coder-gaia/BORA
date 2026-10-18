package dev.bora;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.exception.NotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

class DockerClientCompatibilityIT {

  @Test
  void decodesMissingImageResponsesWithTheExpectedDockerClientException() {
    String missingImage = "bora-compatibility-missing:" + UUID.randomUUID();

    // Força a leitura de uma resposta 404 mesmo quando o Ryuk já está em cache.
    assertThatThrownBy(
            () -> DockerClientFactory.instance().client().inspectImageCmd(missingImage).exec())
        .isInstanceOf(NotFoundException.class);
  }
}
