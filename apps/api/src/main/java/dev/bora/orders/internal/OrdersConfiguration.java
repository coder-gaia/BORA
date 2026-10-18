package dev.bora.orders.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OrdersConfiguration {

  @Bean
  Clock applicationClock() {
    return Clock.systemUTC();
  }
}
