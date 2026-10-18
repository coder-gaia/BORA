package dev.bora.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.ArrayEdgeIntAccess;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DefaultImportRegistry;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.ImportUnit;
import com.graphhopper.routing.ev.Roundabout;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.parsers.CarAccessParser;
import com.graphhopper.routing.util.parsers.OSMRoundaboutParser;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.PMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MotorcycleAccessParserTest {

  private static final int EDGE = 0;
  private static final IntsRef RELATIONS = IntsRef.EMPTY;

  @ParameterizedTest(name = "{0}")
  @MethodSource("accessCases")
  void appliesMotorcycleAccessAndDirectionalPrecedence(
      String name, Map<String, String> tags, boolean forward, boolean backward) {
    Fixture fixture = fixture(new PMap());
    ReaderWay way = way(tags);

    parse(fixture, way);

    assertThat(access(fixture)).as(name).containsExactly(forward, backward);
  }

  static Stream<Arguments> accessCases() {
    return Stream.of(
        rule("residential defaults", true, true),
        rule("motorcycle denied", false, false, "motorcycle", "no"),
        rule("motor vehicles denied", false, false, "motor_vehicle", "no"),
        rule("vehicles denied", false, false, "vehicle", "no"),
        rule("general access denied", false, false, "access", "no"),
        rule("private road blocked", false, false, "access", "private"),
        rule("motorcar restriction does not apply", true, true, "motorcar", "no"),
        rule(
            "motorcycle overrides motor vehicle",
            true,
            true,
            "motorcycle",
            "yes",
            "motor_vehicle",
            "no"),
        rule(
            "motorcycle overrides general access", true, true, "motorcycle", "yes", "access", "no"),
        rule(
            "motorcar permission does not open motorcycles",
            false,
            false,
            "motorcar",
            "yes",
            "motorcycle",
            "no"),
        rule("ordinary one way", true, false, "oneway", "yes"),
        rule("boolean one way", true, false, "oneway", "true"),
        rule("numeric one way", true, false, "oneway", "1"),
        rule("reverse one way", false, true, "oneway", "-1"),
        rule("explicit two way", true, true, "oneway", "no"),
        rule("boolean two way", true, true, "oneway", "false"),
        rule("numeric two way", true, true, "oneway", "0"),
        rule("reversible needs dynamic control", false, false, "oneway", "reversible"),
        rule("alternating needs dynamic control", false, false, "oneway", "alternating"),
        rule("unknown one way is blocked", false, false, "oneway", "unknown"),
        rule(
            "unknown motorcycle one way does not fall back",
            false,
            false,
            "oneway:motorcycle",
            "unknown",
            "oneway",
            "yes"),
        rule(
            "motorcycle one way exception", true, true, "oneway", "yes", "oneway:motorcycle", "no"),
        rule(
            "motor vehicle one way exception",
            true,
            true,
            "oneway",
            "yes",
            "oneway:motor_vehicle",
            "no"),
        rule("vehicle one way exception", true, true, "oneway", "yes", "oneway:vehicle", "no"),
        rule(
            "motorcycle one way takes precedence",
            true,
            false,
            "oneway:motorcycle",
            "yes",
            "oneway:motor_vehicle",
            "no"),
        rule(
            "motorcycle exception takes precedence",
            true,
            true,
            "oneway:motorcycle",
            "no",
            "oneway:motor_vehicle",
            "yes"),
        rule(
            "motorcycle reverse takes precedence",
            false,
            true,
            "oneway:motorcycle",
            "-1",
            "oneway",
            "yes"),
        rule(
            "motor vehicle reverse takes precedence",
            false,
            true,
            "oneway:motor_vehicle",
            "-1",
            "oneway:vehicle",
            "no"),
        rule("roundabout implies one way", true, false, "junction", "roundabout"),
        rule(
            "roundabout unknown one way is blocked",
            false,
            false,
            "junction",
            "roundabout",
            "oneway",
            "unknown"),
        rule("circular junction implies one way", true, false, "junction", "circular"),
        rule(
            "roundabout exception",
            true,
            true,
            "junction",
            "roundabout",
            "oneway:motorcycle",
            "no"),
        rule("roundabout explicit two way", true, true, "junction", "roundabout", "oneway", "no"),
        rule(
            "roundabout reverse motorcycle",
            false,
            true,
            "junction",
            "roundabout",
            "oneway:motorcycle",
            "-1"),
        rule("forward motorcycle denied", false, true, "motorcycle:forward", "no"),
        rule("backward motorcycle denied", true, false, "motorcycle:backward", "no"),
        rule(
            "directional motorcycle beats general motorcycle",
            false,
            true,
            "motorcycle",
            "yes",
            "motorcycle:forward",
            "no"),
        rule(
            "directional motorcycle opens one direction",
            true,
            false,
            "motorcycle",
            "no",
            "motorcycle:forward",
            "yes"),
        rule(
            "motorcycle beats directional motor vehicle",
            true,
            true,
            "motorcycle",
            "yes",
            "motor_vehicle:forward",
            "no"),
        rule(
            "backward motorcycle overrides general access",
            false,
            true,
            "access",
            "no",
            "motorcycle:backward",
            "yes"),
        rule(
            "directional motor vehicle overrides vehicle",
            true,
            false,
            "vehicle",
            "no",
            "motor_vehicle:forward",
            "yes"),
        rule(
            "directional motor vehicle beats directional vehicle",
            true,
            false,
            "motor_vehicle:backward",
            "no",
            "vehicle:backward",
            "yes"),
        rule(
            "both directions denied",
            false,
            false,
            "motorcycle:forward",
            "no",
            "motorcycle:backward",
            "no"),
        rule(
            "forward denial does not reverse one way",
            false,
            false,
            "oneway",
            "yes",
            "motorcycle:forward",
            "no"),
        rule(
            "backward denial does not reverse one way",
            false,
            false,
            "oneway",
            "-1",
            "motorcycle:backward",
            "no"),
        rule(
            "one way exception still respects directional access",
            true,
            false,
            "oneway:motorcycle",
            "no",
            "motorcycle:backward",
            "no"),
        rule(
            "motorcycle permission does not open footways",
            false,
            false,
            "highway",
            "footway",
            "motorcycle",
            "yes"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("barrierCases")
  void retainsBarrierChecksWhenApplyingTheFinalDirectionMask(
      String name, Map<String, Object> nodeTags, boolean permitted) {
    Fixture fixture = fixture(new PMap());
    ReaderWay way =
        way(Map.of("junction", "roundabout", "oneway:motorcycle", "no", "motorcycle", "yes"));
    way.setTag("gh:barrier_edge", true);
    way.setTag("node_tags", List.of(nodeTags, nodeTags));

    parse(fixture, way);

    assertThat(access(fixture)).as(name).containsExactly(permitted, permitted);
  }

  static Stream<Arguments> barrierCases() {
    return Stream.of(
        Arguments.of("bollard", Map.of("barrier", "bollard"), false),
        Arguments.of("motorcycle barrier", Map.of("barrier", "motorcycle_barrier"), false),
        Arguments.of("cycle barrier", Map.of("barrier", "cycle_barrier"), false),
        Arguments.of("locked gate", Map.of("barrier", "gate", "locked", "yes"), false),
        Arguments.of("motorcycle node restriction", Map.of("motorcycle", "no"), false),
        Arguments.of("general node restriction", Map.of("access", "no"), false),
        Arguments.of(
            "explicit motorcycle node exception",
            Map.of("barrier", "bollard", "motorcycle", "yes", "motor_vehicle", "no"),
            true),
        Arguments.of("open gate", Map.of("barrier", "gate"), true));
  }

  @Test
  void clearsOnlyMotorcycleBitsWhenAccessIsDenied() {
    Fixture fixture = fixture(new PMap());
    fixture.motorcycle().setBool(false, EDGE, fixture.flags(), true);
    fixture.motorcycle().setBool(true, EDGE, fixture.flags(), true);
    fixture.car().setBool(false, EDGE, fixture.flags(), true);
    fixture.car().setBool(true, EDGE, fixture.flags(), false);
    fixture.roundabout().setBool(false, EDGE, fixture.flags(), true);

    parse(fixture, way(Map.of("motorcycle", "no")));

    assertThat(access(fixture)).containsExactly(false, false);
    assertThat(fixture.car().getBool(false, EDGE, fixture.flags())).isTrue();
    assertThat(fixture.car().getBool(true, EDGE, fixture.flags())).isFalse();
    assertThat(fixture.roundabout().getBool(false, EDGE, fixture.flags())).isTrue();
  }

  @Test
  void leavesOriginalTagsNodesAndTheCarParserUnchanged() {
    Fixture fixture = fixture(new PMap());
    Map<String, Object> nodeTags = Map.of("barrier", "gate");
    List<Map<String, Object>> barrierNodes = List.of(nodeTags, nodeTags);
    ReaderWay way =
        way(
            Map.of(
                "motorcycle", "yes", "motorcar", "no", "oneway", "yes", "oneway:motorcycle", "no"));
    way.getNodes().add(123);
    way.getNodes().add(456);
    way.setTag("gh:barrier_edge", true);
    way.setTag("node_tags", barrierNodes);
    Map<String, Object> originalTags = Map.copyOf(way.getTags());
    long[] originalNodes = way.getNodes().toArray();

    parse(fixture, way);
    new CarAccessParser(fixture.lookup(), new PMap())
        .handleWayTags(EDGE, fixture.flags(), way, RELATIONS);

    assertThat(access(fixture)).containsExactly(true, true);
    assertThat(fixture.car().getBool(false, EDGE, fixture.flags())).isFalse();
    assertThat(fixture.car().getBool(true, EDGE, fixture.flags())).isFalse();
    assertThat(way.getTags()).isEqualTo(originalTags);
    assertThat(way.getTag("node_tags", List.of())).isSameAs(barrierNodes);
    assertThat(way.getNodes().toArray()).containsExactly(originalNodes);
    assertThat(nodeTags).containsExactlyEntriesOf(Map.of("barrier", "gate"));
  }

  @Test
  void preservesDelegatePropertiesForPrivateAccessAndFords() {
    Fixture privateAccess = fixture(new PMap().putObject("block_private", false));
    parse(privateAccess, way(Map.of("motorcycle", "private")));
    assertThat(access(privateAccess)).containsExactly(true, true);

    Fixture blockedFord = fixture(new PMap().putObject("block_fords", true));
    parse(blockedFord, way(Map.of("ford", "yes")));
    assertThat(access(blockedFord)).containsExactly(false, false);
  }

  @Test
  void registersOnlyMotorcycleAccessAndPreservesTheDefaultCarParser() {
    MotorcycleImportRegistry registry = new MotorcycleImportRegistry();
    Fixture fixture = fixture(new PMap());
    ImportUnit motorcycle = registry.createImportUnit("motorcycle_access");

    assertThat(motorcycle.getRequiredImportUnits()).containsExactly(Roundabout.KEY);
    assertThat(motorcycle.getCreateEncodedValue().apply(new PMap()).getName())
        .isEqualTo("motorcycle_access");
    assertThat(motorcycle.getCreateEncodedValue().apply(new PMap()).isStoreTwoDirections())
        .isTrue();
    assertThat(motorcycle.getCreateTagParser().apply(fixture.lookup(), new PMap()))
        .isInstanceOf(MotorcycleAccessParser.class);
    assertThat(
            registry
                .createImportUnit("car_access")
                .getCreateTagParser()
                .apply(fixture.lookup(), new PMap()))
        .isExactlyInstanceOf(CarAccessParser.class);
    assertThat(registry.createImportUnit("car_average_speed").getRequiredImportUnits())
        .isEqualTo(
            new DefaultImportRegistry()
                .createImportUnit("car_average_speed")
                .getRequiredImportUnits());
  }

  private static Arguments rule(String name, boolean forward, boolean backward, String... tags) {
    Map<String, String> values = new LinkedHashMap<>();
    for (int index = 0; index < tags.length; index += 2) {
      values.put(tags[index], tags[index + 1]);
    }
    return Arguments.of(name, values, forward, backward);
  }

  private static ReaderWay way(Map<String, String> tags) {
    ReaderWay way = new ReaderWay(1);
    way.setTag("highway", "residential");
    tags.forEach(way::setTag);
    return way;
  }

  private static Fixture fixture(PMap properties) {
    BooleanEncodedValue motorcycle = VehicleAccess.create("motorcycle");
    BooleanEncodedValue roundabout = Roundabout.create();
    BooleanEncodedValue car = VehicleAccess.create("car");
    EncodingManager lookup =
        EncodingManager.start().add(motorcycle).add(roundabout).add(car).build();
    EdgeIntAccess flags = ArrayEdgeIntAccess.createFromBytes(lookup.getBytesForFlags());
    return new Fixture(
        motorcycle,
        roundabout,
        car,
        lookup,
        flags,
        new MotorcycleAccessParser(motorcycle, roundabout, properties));
  }

  private static void parse(Fixture fixture, ReaderWay way) {
    new OSMRoundaboutParser(fixture.roundabout())
        .handleWayTags(EDGE, fixture.flags(), way, RELATIONS);
    fixture.parser().handleWayTags(EDGE, fixture.flags(), way, RELATIONS);
  }

  private static boolean[] access(Fixture fixture) {
    return new boolean[] {
      fixture.motorcycle().getBool(false, EDGE, fixture.flags()),
      fixture.motorcycle().getBool(true, EDGE, fixture.flags())
    };
  }

  private record Fixture(
      BooleanEncodedValue motorcycle,
      BooleanEncodedValue roundabout,
      BooleanEncodedValue car,
      EncodingManager lookup,
      EdgeIntAccess flags,
      MotorcycleAccessParser parser) {}
}
