package dev.bora.routing.internal;

import com.graphhopper.routing.ev.DefaultImportRegistry;
import com.graphhopper.routing.ev.ImportUnit;
import com.graphhopper.routing.ev.Roundabout;
import com.graphhopper.routing.ev.VehicleAccess;

public final class MotorcycleImportRegistry extends DefaultImportRegistry {

  static final String PARSER_VERSION = "1";
  private static final String MOTORCYCLE_ACCESS = VehicleAccess.key("motorcycle");

  @Override
  public ImportUnit createImportUnit(String name) {
    if (MOTORCYCLE_ACCESS.equals(name)) {
      return ImportUnit.create(
          name,
          properties -> VehicleAccess.create("motorcycle"),
          (lookup, properties) ->
              new MotorcycleAccessParser(
                  lookup.getBooleanEncodedValue(MOTORCYCLE_ACCESS),
                  lookup.getBooleanEncodedValue(Roundabout.KEY),
                  properties),
          Roundabout.KEY);
    }
    return super.createImportUnit(name);
  }
}
