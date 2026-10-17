package dev.bora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic
@SpringBootApplication
public class BoraApplication {

  public static void main(String[] args) {
    SpringApplication.run(BoraApplication.class, args);
  }
}
