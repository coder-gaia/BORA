package dev.bora;

import dev.bora.routing.RoutingTools;
import java.util.OptionalInt;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic
@SpringBootApplication
public class BoraApplication {

  public static void main(String[] args) {
    OptionalInt exitCode = RoutingTools.run(args);
    if (exitCode.isPresent()) {
      System.exit(exitCode.getAsInt());
      return;
    }
    SpringApplication.run(BoraApplication.class, args);
  }
}
