package dev.bora.routing.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RoutingProperties.class)
class RoutingConfiguration {
  @Bean(initMethod = "initialize", destroyMethod = "close")
  GraphHopperRoutingEngine routingService(RoutingProperties properties) {
    return new GraphHopperRoutingEngine(
        RoutingGraphSpec.loadManifest(properties.manifest()),
        properties.graphRoot(),
        properties.simulationSpeed(),
        properties.maxSnapDistanceMeters());
  }

  @Bean
  HealthIndicator routingHealthIndicator(GraphHopperRoutingEngine engine) {
    return () -> engine.isReady() ? Health.up().build() : Health.down().build();
  }
}
