package dev.bora.routing.internal;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("bora.routing")
record RoutingProperties(
    @DefaultValue("target/routing-graphs") Path graphRoot,
    @DefaultValue("routing/data/manifest.json") String manifest,
    @DefaultValue("3") double simulationSpeed,
    @DefaultValue("150") double maxSnapDistanceMeters) {}
