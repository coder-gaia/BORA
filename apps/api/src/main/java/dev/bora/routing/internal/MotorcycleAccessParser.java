package dev.bora.routing.internal;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.util.TransportationMode;
import com.graphhopper.routing.util.parsers.CarAccessParser;
import com.graphhopper.routing.util.parsers.OSMRoadAccessParser;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.PMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class MotorcycleAccessParser implements TagParser {

  private static final int FORWARD = 1;
  private static final int BACKWARD = 2;
  private static final List<String> ACCESS_KEYS =
      List.copyOf(OSMRoadAccessParser.toOSMRestrictions(TransportationMode.MOTORCYCLE));
  private static final List<String> ONEWAY_KEYS =
      List.of("oneway:motorcycle", "oneway:motor_vehicle", "oneway:vehicle", "oneway");

  private final BooleanEncodedValue motorcycleAccess;
  private final BooleanEncodedValue roundabout;
  private final CarAccessParser delegate;

  public MotorcycleAccessParser(
      BooleanEncodedValue motorcycleAccess, BooleanEncodedValue roundabout, PMap properties) {
    this.motorcycleAccess = Objects.requireNonNull(motorcycleAccess);
    this.roundabout = Objects.requireNonNull(roundabout);
    if (!motorcycleAccess.isStoreTwoDirections()) {
      throw new IllegalArgumentException("O acesso de moto precisa armazenar os dois sentidos.");
    }
    delegate =
        new CarAccessParser(
            motorcycleAccess, roundabout, Objects.requireNonNull(properties), ACCESS_KEYS);
  }

  @Override
  public void handleWayTags(
      int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
    int directions = onewayMask(edgeId, edgeIntAccess, way);
    boolean forward = permitted(edgeId, edgeIntAccess, way, relationFlags, "forward");
    boolean backward = permitted(edgeId, edgeIntAccess, way, relationFlags, "backward");
    motorcycleAccess.setBool(false, edgeId, edgeIntAccess, forward && (directions & FORWARD) != 0);
    motorcycleAccess.setBool(true, edgeId, edgeIntAccess, backward && (directions & BACKWARD) != 0);
  }

  private boolean permitted(
      int edgeId,
      EdgeIntAccess edgeIntAccess,
      ReaderWay original,
      IntsRef relationFlags,
      String direction) {
    ReaderWay normalized = new ReaderWay(original.getId());
    normalized.getNodes().addAll(original.getNodes());
    normalized.setTags(new LinkedHashMap<>(original.getTags()));
    String effectiveAccess = effectiveAccess(original, direction);
    if (effectiveAccess == null) {
      normalized.removeTag("motorcycle");
    } else {
      normalized.setTag("motorcycle", effectiveAccess);
    }
    normalized.setTag("oneway", "no");
    for (String key : ACCESS_KEYS) {
      normalized.removeTag(key + ":forward");
      normalized.removeTag(key + ":backward");
    }

    // O delegado pode retornar sem gravar. Os demais valores codificados ficam intactos.
    motorcycleAccess.setBool(false, edgeId, edgeIntAccess, false);
    motorcycleAccess.setBool(true, edgeId, edgeIntAccess, false);
    delegate.handleWayTags(edgeId, edgeIntAccess, normalized, relationFlags);
    // Rotatórias forçam um sentido no delegado; a máscara de moto é aplicada só no final.
    return motorcycleAccess.getBool(false, edgeId, edgeIntAccess)
        || motorcycleAccess.getBool(true, edgeId, edgeIntAccess);
  }

  private String effectiveAccess(ReaderWay way, String direction) {
    for (String key : ACCESS_KEYS) {
      String directional = way.getTag(key + ":" + direction);
      if (directional != null) {
        return directional;
      }
      String general = way.getTag(key);
      if (general != null) {
        return general;
      }
    }
    return null;
  }

  private int onewayMask(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way) {
    String oneway = way.getFirstValue(ONEWAY_KEYS);
    return switch (oneway) {
      case "yes", "true", "1" -> FORWARD;
      case "-1" -> BACKWARD;
      case "no", "false", "0" -> FORWARD | BACKWARD;
      case "" -> roundabout.getBool(false, edgeId, edgeIntAccess) ? FORWARD : FORWARD | BACKWARD;
      default -> 0;
    };
  }
}
