package dev.bora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
class DomainSchemaIT {

  private static final String AREA = "10000000-0000-4000-8000-000000000001";
  private static final String STORE = "20000000-0000-4000-8000-000000000001";
  private static final String CREATED = "'2026-10-18T12:00:00Z'::timestamptz";
  private static final String POINT = "ST_GeogFromText('SRID=4326;POINT(-46.6333 -23.5505)')";
  private static final String EMPTY_POINT = "ST_GeogFromText('SRID=4326;POINT EMPTY')";

  @Container @ServiceConnection
  static final PostgreSQLContainer database =
      new PostgreSQLContainer(
          DockerImageName.parse(System.getProperty("bora.postgis.image", "bora-postgis:18.6-3.6.4"))
              .asCompatibleSubstituteFor("postgres"));

  @Autowired DataSource dataSource;

  @Test
  void remainsCompatibleWithThePreviousReleaseMigrationSet(@TempDir Path previousMigrations)
      throws Exception {
    try (InputStream original =
        getClass().getResourceAsStream("/db/migration/V1__enable_postgis.sql")) {
      assertThat(original).isNotNull();
      Files.copy(original, previousMigrations.resolve("V1__enable_postgis.sql"));
    }
    Flyway previousRelease =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:" + previousMigrations.toAbsolutePath())
            .load();
    previousRelease.validate();
    assertThat(previousRelease.migrate().migrationsExecuted).isZero();
    try (Connection connection = dataSource.getConnection()) {
      assertThat(scalar(connection, "SELECT count(*) FROM stores")).isEqualTo(40);
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('1', '2', '3')"))
          .isEqualTo(3);
    }
  }

  @Test
  void geographyColumnsRequireTheExpectedShapeAndCoordinateReference() throws Exception {
    List<String> types = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                """
                SELECT c.relname || '.' || a.attname || '=' || format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND a.attnum > 0 AND NOT a.attisdropped
                  AND a.atttypid = 'geography'::regtype
                """)) {
      while (rows.next()) {
        types.add(rows.getString(1));
      }
    }
    assertThat(types)
        .containsExactlyInAnyOrder(
            "service_areas.polygon=geography(Polygon,4326)",
            "stores.location=geography(Point,4326)",
            "couriers.location=geography(Point,4326)",
            "orders.dropoff=geography(Point,4326)",
            "delivery_tracks.position=geography(Point,4326)");
  }

  @Test
  void migratesAndSeedsFortyFictionalStoresInsideTheApproximateDemonstrationArea()
      throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('1', '2', '3')"))
          .isEqualTo(3);
      assertThat(scalar(connection, "SELECT count(*) FROM service_areas")).isEqualTo(1);
      assertThat(scalar(connection, "SELECT count(*) FROM stores")).isEqualTo(40);
      assertThat(
              scalar(
                  connection,
                  """
                  SELECT count(*) FROM stores s JOIN service_areas a ON a.id = s.service_area_id
                  WHERE s.simulated AND ST_Covers(a.polygon, s.location)
                    AND NOT ST_IsEmpty(s.location::geometry) AND ST_IsValid(s.location::geometry)
                    AND ST_X(s.location::geometry) BETWEEN -46.74 AND -46.55
                    AND ST_Y(s.location::geometry) BETWEEN -23.64 AND -23.49
                  """))
          .isEqualTo(40);
      assertThat(
              scalar(
                  connection,
                  """
                  SELECT count(*) FROM service_areas
                  WHERE name LIKE '%aproximado%' AND ST_IsValid(polygon::geometry)
                    AND NOT ST_IsEmpty(polygon::geometry)
                    AND ST_XMin(polygon::geometry) >= -46.74
                    AND ST_XMax(polygon::geometry) <= -46.55
                    AND ST_YMin(polygon::geometry) >= -23.64
                    AND ST_YMax(polygon::geometry) <= -23.49
                  """))
          .isEqualTo(1);
    }
  }

  @TestFactory
  Stream<DynamicTest> rejectsEveryNamedCheckConstraintThroughDirectSql() {
    return checkViolations().stream()
        .map(
            violation ->
                DynamicTest.dynamicTest(
                    violation.name(),
                    () ->
                        inRollback(
                            (connection, fixture) ->
                                expectSqlError(
                                    connection,
                                    violation.sql().apply(fixture),
                                    "23514",
                                    violation.constraint()))));
  }

  @TestFactory
  Stream<DynamicTest> rejectsBrokenForeignKeysThroughDirectSql() {
    UUID missing = UUID.randomUUID();
    List<Violation> violations =
        List.of(
            mutation("stores", "service_area_id = '" + missing + "'", "fk_stores_service_area"),
            mutation("orders", "store_id = '" + missing + "'", "fk_orders_store"),
            mutation(
                "orders",
                "status = 'CANCELED', next_attempt_at = NULL, canceled_at = "
                    + CREATED
                    + ", assigned_at = "
                    + CREATED
                    + ", courier_id = '"
                    + missing
                    + "'",
                "fk_orders_courier"),
            mutation("offers", "order_id = '" + missing + "'", "fk_offers_order"),
            mutation("offers", "courier_id = '" + missing + "'", "fk_offers_courier"),
            new Violation(
                "fk_order_events_order",
                fixture ->
                    "INSERT INTO order_events (order_id, type, payload, actor, order_version) VALUES ('"
                        + missing
                        + "', 'CREATED', '{}', 'test', 0)",
                "fk_order_events_order"),
            mutation(
                "delivery_tracks", "order_id = '" + missing + "'", "fk_delivery_tracks_order"));
    return violations.stream()
        .map(
            violation ->
                DynamicTest.dynamicTest(
                    violation.name(),
                    () ->
                        inRollback(
                            (connection, fixture) ->
                                expectSqlError(
                                    connection,
                                    violation.sql().apply(fixture),
                                    "23503",
                                    violation.constraint()))));
  }

  @TestFactory
  Stream<DynamicTest> rejectsNullRequiredColumns() {
    return Stream.of(
            mutation("service_areas", "polygon = NULL", "polygon"),
            mutation("stores", "service_area_id = NULL", "service_area_id"),
            mutation("stores", "location = NULL", "location"),
            mutation("couriers", "status = NULL", "status"),
            mutation("orders", "public_code = NULL", "public_code"),
            mutation("orders", "sla_deadline = NULL", "sla_deadline"),
            mutation("offers", "expires_at = NULL", "expires_at"),
            mutation("delivery_tracks", "position = NULL", "position"))
        .map(
            violation ->
                DynamicTest.dynamicTest(
                    violation.name(),
                    () ->
                        inRollback(
                            (connection, fixture) ->
                                expectSqlError(
                                    connection, violation.sql().apply(fixture), "23502", null))));
  }

  @TestFactory
  Stream<DynamicTest> protectsEventsFromUpdateDeleteAndTruncate() {
    return Stream.of(
            "UPDATE order_events SET actor = 'changed' WHERE order_id = '%s'",
            "DELETE FROM order_events WHERE order_id = '%s'",
            "TRUNCATE order_events",
            "TRUNCATE orders CASCADE")
        .map(
            sql ->
                DynamicTest.dynamicTest(
                    sql,
                    () ->
                        inRollback(
                            (connection, fixture) ->
                                expectSqlError(
                                    connection, sql.formatted(fixture.order()), "55000", null))));
  }

  @Test
  void preventsDeletingAnOrderWhoseEventsMustBePreserved() throws Exception {
    inRollback(
        (connection, fixture) -> {
          execute(connection, "DELETE FROM offers WHERE order_id = '" + fixture.order() + "'");
          execute(
              connection, "DELETE FROM delivery_tracks WHERE order_id = '" + fixture.order() + "'");
          expectSqlError(
              connection,
              "DELETE FROM orders WHERE id = '" + fixture.order() + "'",
              "23503",
              "fk_order_events_order");
        });
  }

  @Test
  void preventsDuplicatePublicCodesAndTrackSamples() throws Exception {
    inRollback(
        (connection, fixture) -> {
          UUID second = insertOrder(connection, "SEARCHING", null);
          expectSqlError(
              connection,
              "UPDATE orders SET public_code = (SELECT public_code FROM orders WHERE id = '"
                  + fixture.order()
                  + "') WHERE id = '"
                  + second
                  + "'",
              "23505",
              "uq_orders_public_code");
        });
    inRollback(
        (connection, fixture) ->
            expectSqlError(
                connection,
                "INSERT INTO delivery_tracks SELECT * FROM delivery_tracks WHERE order_id = '"
                    + fixture.order()
                    + "'",
                "23505",
                "pk_delivery_tracks"));
  }

  @Test
  void allowsOnlyOnePendingOfferPerOrderAndPerCourier() throws Exception {
    inRollback(
        (connection, fixture) -> {
          UUID secondCourier = insertCourier(connection, "AVAILABLE", -46.634, -23.551);
          expectSqlError(
              connection,
              offerInsert(UUID.randomUUID(), fixture.order(), secondCourier, "PENDING", null),
              "23505",
              "uq_offers_pending_order");
        });
    inRollback(
        (connection, fixture) -> {
          UUID secondOrder = insertOrder(connection, "SEARCHING", null);
          expectSqlError(
              connection,
              offerInsert(UUID.randomUUID(), secondOrder, fixture.courier(), "PENDING", null),
              "23505",
              "uq_offers_pending_courier");
        });
  }

  @TestFactory
  Stream<DynamicTest> allowsOnlyOneActiveOrderPerCourier() {
    return Stream.of("ASSIGNED", "AT_STORE", "PICKED_UP")
        .map(
            status ->
                DynamicTest.dynamicTest(
                    status,
                    () ->
                        inRollback(
                            (connection, fixture) -> {
                              insertOrder(connection, status, fixture.courier());
                              expectSqlError(
                                  connection,
                                  orderInsert(UUID.randomUUID(), status, fixture.courier()),
                                  "23505",
                                  "uq_orders_active_courier");
                            })));
  }

  @Test
  void redispatchPreservesTwoHistoricalAcceptedOffersAndTheEventTimeline() throws Exception {
    inRollback(
        (connection, fixture) -> {
          execute(
              connection,
              "UPDATE offers SET status = 'ACCEPTED', responded_at = "
                  + CREATED
                  + " + interval '10 seconds' WHERE id = '"
                  + fixture.offer()
                  + "'");
          execute(
              connection,
              "UPDATE orders SET status = 'ASSIGNED', courier_id = '"
                  + fixture.courier()
                  + "', assigned_at = "
                  + CREATED
                  + " + interval '10 seconds', next_attempt_at = NULL, version = 1 WHERE id = '"
                  + fixture.order()
                  + "'");
          insertEvent(connection, fixture.order(), "ASSIGNED", 1);
          execute(
              connection,
              "UPDATE orders SET status = 'SEARCHING', courier_id = NULL, assigned_at = NULL, "
                  + "next_attempt_at = "
                  + CREATED
                  + " + interval '15 seconds', version = 2 WHERE id = '"
                  + fixture.order()
                  + "'");
          insertEvent(connection, fixture.order(), "REASSIGNED", 2);
          UUID replacement = insertCourier(connection, "AVAILABLE", -46.634, -23.551);
          execute(
              connection,
              offerInsert(
                  UUID.randomUUID(),
                  fixture.order(),
                  replacement,
                  "ACCEPTED",
                  CREATED + " + interval '20 seconds'"));
          execute(
              connection,
              "UPDATE orders SET status = 'ASSIGNED', courier_id = '"
                  + replacement
                  + "', assigned_at = "
                  + CREATED
                  + " + interval '20 seconds', next_attempt_at = NULL, version = 3 WHERE id = '"
                  + fixture.order()
                  + "'");
          insertEvent(connection, fixture.order(), "ASSIGNED", 3);
          assertThat(
                  scalar(
                      connection,
                      "SELECT count(*) FROM offers WHERE order_id = '"
                          + fixture.order()
                          + "' AND status = 'ACCEPTED'"))
              .isEqualTo(2);
          assertThat(
                  scalar(
                      connection,
                      "SELECT count(*) FROM order_events WHERE order_id = '"
                          + fixture.order()
                          + "'"))
              .isEqualTo(4);
          assertThat(
                  scalar(
                      connection,
                      "SELECT count(*) FROM orders WHERE id = '"
                          + fixture.order()
                          + "' AND courier_id = '"
                          + replacement
                          + "' AND version = 3"))
              .isEqualTo(1);
        });
  }

  @Test
  void concurrentTransactionsCannotReserveTheSameCourierTwice() throws Exception {
    UUID courier;
    UUID firstOrder;
    UUID secondOrder;
    try (Connection connection = dataSource.getConnection()) {
      courier = insertCourier(connection, "AVAILABLE", -46.634, -23.551);
      firstOrder = insertOrder(connection, "SEARCHING", null);
      secondOrder = insertOrder(connection, "SEARCHING", null);
    }
    List<String> results =
        raceTransactions(
            connection -> {
              execute(
                  connection, offerInsert(UUID.randomUUID(), firstOrder, courier, "PENDING", null));
              return "COMMITTED";
            },
            connection -> {
              execute(
                  connection,
                  offerInsert(UUID.randomUUID(), secondOrder, courier, "PENDING", null));
              return "COMMITTED";
            });
    assertThat(results).containsExactlyInAnyOrder("COMMITTED", "23505:uq_offers_pending_courier");
    try (Connection connection = dataSource.getConnection()) {
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM offers WHERE courier_id = '"
                      + courier
                      + "' AND status = 'PENDING'"))
          .isEqualTo(1);
    }
  }

  @Test
  void concurrentDeliveryWithTheSameExpectedVersionCommitsOnlyOnce() throws Exception {
    UUID order;
    try (Connection connection = dataSource.getConnection()) {
      UUID courier = insertCourier(connection, "BUSY", -46.634, -23.551);
      order = insertOrder(connection, "PICKED_UP", courier);
    }
    TransactionAction delivery =
        connection -> {
          int updated =
              execute(
                  connection,
                  "UPDATE orders SET status = 'DELIVERED', delivered_at = "
                      + CREATED
                      + " + interval '20 minutes', version = version + 1 WHERE id = '"
                      + order
                      + "' AND version = 0 AND status = 'PICKED_UP'");
          if (updated == 1) {
            insertEvent(connection, order, "DELIVERED", 1);
          }
          return Integer.toString(updated);
        };
    assertThat(raceTransactions(delivery, delivery)).containsExactlyInAnyOrder("1", "0");
    try (Connection connection = dataSource.getConnection()) {
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM orders WHERE id = '"
                      + order
                      + "' AND status = 'DELIVERED' AND version = 1"))
          .isEqualTo(1);
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM order_events WHERE order_id = '"
                      + order
                      + "' AND type = 'DELIVERED' AND order_version = 1"))
          .isEqualTo(1);
    }
  }

  @Test
  void spatialCandidateQueryFiltersByRadiusAndAvailabilityAndOrdersNearestFirst() throws Exception {
    inRollback(
        (connection, fixture) -> {
          UUID near = insertCourier(connection, "AVAILABLE", -46.6334, -23.5506);
          UUID farther = insertCourier(connection, "AVAILABLE", -46.6400, -23.5505);
          UUID outside = insertCourier(connection, "AVAILABLE", -46.7000, -23.5505);
          UUID offline = insertCourier(connection, "OFFLINE", -46.63331, -23.55051);
          UUID offered = insertCourier(connection, "OFFERED", -46.63332, -23.55052);
          UUID busy = insertCourier(connection, "BUSY", -46.63333, -23.55053);
          String ids =
              "'"
                  + String.join(
                      "','",
                      Stream.of(near, farther, outside, offline, offered, busy)
                          .map(UUID::toString)
                          .toList())
                  + "'";
          List<UUID> actual = new ArrayList<>();
          try (Statement statement = connection.createStatement();
              ResultSet rows =
                  statement.executeQuery(
                      "SELECT id FROM couriers WHERE id IN ("
                          + ids
                          + ") AND status = 'AVAILABLE' AND ST_DWithin(location, "
                          + POINT
                          + ", 2000) ORDER BY location <-> "
                          + POINT
                          + " LIMIT 8")) {
            while (rows.next()) {
              actual.add(rows.getObject(1, UUID.class));
            }
          }
          assertThat(actual).containsExactly(near, farther);
          assertThat(
                  scalar(
                      connection,
                      """
                      SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                        AND indexname IN ('idx_service_areas_polygon', 'idx_stores_location',
                          'idx_couriers_location', 'idx_couriers_available_location',
                          'idx_orders_dropoff', 'idx_delivery_tracks_position')
                        AND indexdef LIKE '%USING gist%'
                      """))
              .isEqualTo(6);
        });
  }

  private List<Violation> checkViolations() {
    List<Violation> violations = new ArrayList<>();
    violations.add(mutation("service_areas", "name = '   '", "ck_service_areas_name"));
    violations.add(
        mutation(
            "service_areas",
            "polygon = ST_GeogFromText('SRID=4326;POLYGON EMPTY')",
            "ck_service_areas_polygon"));
    violations.add(
        new Violation(
            "self-intersecting polygon",
            fixture ->
                "UPDATE service_areas SET polygon = ST_GeogFromText('SRID=4326;POLYGON((-46.7 -23.6,-46.6 -23.5,-46.7 -23.5,-46.6 -23.6,-46.7 -23.6))') WHERE id = '"
                    + AREA
                    + "'",
            "ck_service_areas_polygon"));
    violations.add(mutation("stores", "name = ''", "ck_stores_name"));
    violations.add(mutation("stores", "location = " + EMPTY_POINT, "ck_stores_location"));
    violations.add(mutation("couriers", "name = ' '", "ck_couriers_name"));
    violations.add(mutation("couriers", "status = 'UNKNOWN'", "ck_couriers_status"));
    violations.add(mutation("couriers", "location = " + EMPTY_POINT, "ck_couriers_location"));
    violations.add(
        mutation("couriers", "location_updated_at = NULL", "ck_couriers_location_timestamp"));
    violations.add(
        mutation(
            "couriers",
            "location = NULL, location_updated_at = NULL",
            "ck_couriers_online_location"));
    violations.add(mutation("couriers", "version = -1", "ck_couriers_version"));
    violations.add(mutation("orders", "public_code = 'short'", "ck_orders_public_code"));
    violations.add(mutation("orders", "public_code = repeat('!', 20)", "ck_orders_public_code"));
    violations.add(mutation("orders", "dropoff = " + EMPTY_POINT, "ck_orders_dropoff"));
    violations.add(
        mutation("orders", "status = 'UNKNOWN', next_attempt_at = NULL", "ck_orders_status"));
    violations.add(
        new Violation(
            "searching order cannot have a courier",
            fixture ->
                update("orders", "courier_id = '" + fixture.courier() + "'", fixture.order()),
            "ck_orders_courier"));
    violations.add(
        new Violation(
            "canceled order preserves assignment and courier together",
            fixture ->
                update(
                    "orders",
                    "status = 'CANCELED', next_attempt_at = NULL, canceled_at = "
                        + CREATED
                        + ", courier_id = '"
                        + fixture.courier()
                        + "'",
                    fixture.order()),
            "ck_orders_courier"));
    violations.add(
        mutation("orders", "confirmation_code_hash = ' '", "ck_orders_confirmation_hash"));
    violations.add(
        mutation(
            "orders", "sla_deadline = " + CREATED + " - interval '1 second'", "ck_orders_sla"));
    violations.add(mutation("orders", "next_attempt_at = NULL", "ck_orders_search_schedule"));
    violations.add(
        mutation(
            "orders",
            "next_attempt_at = " + CREATED + " - interval '1 second'",
            "ck_orders_search_schedule"));
    violations.add(mutation("orders", "search_radius_m = 0", "ck_orders_search_radius"));
    violations.add(mutation("orders", "version = -1", "ck_orders_version"));
    violations.add(mutation("orders", "assigned_at = " + CREATED, "ck_orders_timeline"));
    for (String status : List.of("ASSIGNED", "AT_STORE", "PICKED_UP", "DELIVERED")) {
      int completedMilestones =
          switch (status) {
            case "AT_STORE" -> 1;
            case "PICKED_UP" -> 2;
            case "DELIVERED" -> 3;
            default -> 0;
          };
      violations.add(
          new Violation(
              "missing milestone for " + status,
              fixture ->
                  update(
                      "orders",
                      "status = '"
                          + status
                          + "', courier_id = '"
                          + fixture.courier()
                          + "', next_attempt_at = NULL"
                          + milestones(completedMilestones),
                      fixture.order()),
              "ck_orders_timeline"));
      violations.add(
          new Violation(
              status + " requires a courier",
              fixture ->
                  update(
                      "orders",
                      "status = '"
                          + status
                          + "', next_attempt_at = NULL"
                          + milestones(completedMilestones + 1),
                      fixture.order()),
              "ck_orders_courier"));
    }
    for (int milestone = 1; milestone <= 4; milestone++) {
      String column =
          switch (milestone) {
            case 1 -> "assigned_at";
            case 2 -> "at_store_at";
            case 3 -> "picked_up_at";
            default -> "delivered_at";
          };
      int previousMinute = milestone - 2;
      String invalidMilestones =
          milestones(4)
              .replace(
                  column + " = " + CREATED + " + interval '" + milestone + " minutes'",
                  column + " = " + CREATED + " + interval '" + previousMinute + " minutes'");
      violations.add(
          new Violation(
              column + " cannot precede the previous milestone",
              fixture ->
                  update(
                      "orders",
                      "status = 'DELIVERED', courier_id = '"
                          + fixture.courier()
                          + "', next_attempt_at = NULL"
                          + invalidMilestones,
                      fixture.order()),
              "ck_orders_timeline"));
    }
    violations.add(
        mutation("orders", "status = 'CANCELED', next_attempt_at = NULL", "ck_orders_timeline"));
    violations.add(
        mutation(
            "orders",
            "status = 'CANCELED', next_attempt_at = NULL, canceled_at = "
                + CREATED
                + " - interval '1 second'",
            "ck_orders_timeline"));
    violations.add(mutation("offers", "status = 'UNKNOWN'", "ck_offers_status"));
    violations.add(mutation("offers", "eta_to_pickup_s = -1", "ck_offers_eta"));
    violations.add(mutation("offers", "expires_at = " + CREATED, "ck_offers_expiration"));
    violations.add(mutation("offers", "responded_at = " + CREATED, "ck_offers_response"));
    violations.add(mutation("offers", "status = 'ACCEPTED'", "ck_offers_response"));
    violations.add(
        mutation(
            "offers",
            "status = 'ACCEPTED', responded_at = expires_at",
            "ck_offers_response_deadline"));
    violations.add(
        mutation(
            "offers",
            "status = 'EXPIRED', responded_at = " + CREATED,
            "ck_offers_response_deadline"));
    for (String column : List.of("type", "actor")) {
      violations.add(
          eventInsertViolation(column + " blank", column, "' '", "ck_order_events_" + column));
    }
    violations.add(
        eventInsertViolation("payload array", "payload", "'[]'", "ck_order_events_payload"));
    violations.add(
        eventInsertViolation(
            "negative event version", "order_version", "-1", "ck_order_events_version"));
    violations.add(
        mutation("delivery_tracks", "position = " + EMPTY_POINT, "ck_delivery_tracks_position"));
    return violations;
  }

  private static String milestones(int count) {
    StringBuilder sql = new StringBuilder();
    List<String> columns = List.of("assigned_at", "at_store_at", "picked_up_at", "delivered_at");
    for (int index = 0; index < count; index++) {
      sql.append(", ")
          .append(columns.get(index))
          .append(" = ")
          .append(CREATED)
          .append(" + interval '")
          .append(index + 1)
          .append(" minutes'");
    }
    return sql.toString();
  }

  private static Violation eventInsertViolation(
      String name, String column, String value, String constraint) {
    return new Violation(
        name,
        fixture ->
            "INSERT INTO order_events (order_id, type, payload, actor, order_version) VALUES ('"
                + fixture.order()
                + "', "
                + (column.equals("type") ? value : "'CREATED'")
                + ", "
                + (column.equals("payload") ? value : "'{}'")
                + ", "
                + (column.equals("actor") ? value : "'test'")
                + ", "
                + (column.equals("order_version") ? value : "0")
                + ")",
        constraint);
  }

  private static Violation mutation(String table, String assignment, String constraint) {
    return new Violation(
        constraint + ": " + assignment,
        fixture ->
            update(
                table,
                assignment,
                switch (table) {
                  case "service_areas" -> UUID.fromString(AREA);
                  case "stores" -> UUID.fromString(STORE);
                  case "couriers" -> fixture.courier();
                  case "orders", "delivery_tracks" -> fixture.order();
                  case "offers" -> fixture.offer();
                  default -> throw new IllegalArgumentException(table);
                }),
        constraint);
  }

  private static String update(String table, String assignment, UUID id) {
    return "UPDATE "
        + table
        + " SET "
        + assignment
        + " WHERE "
        + (table.equals("delivery_tracks") ? "order_id" : "id")
        + " = '"
        + id
        + "'";
  }

  private void inRollback(FixtureAction action) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        UUID courier = insertCourier(connection, "AVAILABLE", -46.6333, -23.5505);
        UUID order = insertOrder(connection, "SEARCHING", null);
        UUID offer = UUID.randomUUID();
        execute(connection, offerInsert(offer, order, courier, "PENDING", null));
        insertEvent(connection, order, "CREATED", 0);
        execute(
            connection,
            "INSERT INTO delivery_tracks (order_id, recorded_at, position) VALUES ('"
                + order
                + "', "
                + CREATED
                + ", "
                + POINT
                + ")");
        action.run(connection, new Fixture(courier, order, offer));
      } finally {
        connection.rollback();
      }
    }
  }

  private List<String> raceTransactions(TransactionAction first, TransactionAction second)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<String>> futures = new ArrayList<>();
      for (TransactionAction action : List.of(first, second)) {
        futures.add(
            executor.submit(
                () -> {
                  try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    execute(connection, "SET LOCAL statement_timeout = '15s'");
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("Second transaction did not become ready");
                    }
                    try {
                      String result = action.run(connection);
                      connection.commit();
                      return result;
                    } catch (PSQLException exception) {
                      connection.rollback();
                      return exception.getSQLState()
                          + ":"
                          + exception.getServerErrorMessage().getConstraint();
                    }
                  }
                }));
      }
      try {
        assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
      } finally {
        start.countDown();
      }
      List<String> results = new ArrayList<>();
      for (Future<String> future : futures) {
        results.add(future.get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS));
      }
      return results;
    }
  }

  private static UUID insertCourier(Connection connection, String status, double lon, double lat)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO couriers (id, name, status, location, location_updated_at, simulated)
            VALUES (?, 'Test courier', ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
              '2026-10-18T12:00:00Z', true)
            """)) {
      statement.setObject(1, id);
      statement.setString(2, status);
      statement.setDouble(3, lon);
      statement.setDouble(4, lat);
      statement.executeUpdate();
    }
    return id;
  }

  private static UUID insertOrder(Connection connection, String status, UUID courier)
      throws SQLException {
    UUID id = UUID.randomUUID();
    execute(connection, orderInsert(id, status, courier));
    return id;
  }

  private static String orderInsert(UUID id, String status, UUID courier) {
    boolean assigned = List.of("ASSIGNED", "AT_STORE", "PICKED_UP", "DELIVERED").contains(status);
    boolean atStore = List.of("AT_STORE", "PICKED_UP", "DELIVERED").contains(status);
    boolean pickedUp = List.of("PICKED_UP", "DELIVERED").contains(status);
    return "INSERT INTO orders (id, public_code, store_id, dropoff, status, courier_id, "
        + "sla_deadline, next_attempt_at, created_at, assigned_at, at_store_at, picked_up_at, "
        + "delivered_at, canceled_at) VALUES ('"
        + id
        + "', '"
        + UUID.randomUUID().toString().replace("-", "")
        + "', '"
        + STORE
        + "', "
        + POINT
        + ", '"
        + status
        + "', "
        + (courier == null ? "NULL" : "'" + courier + "'")
        + ", "
        + CREATED
        + " + interval '30 minutes', "
        + (status.equals("SEARCHING") ? CREATED : "NULL")
        + ", "
        + CREATED
        + ", "
        + (assigned ? CREATED + " + interval '1 minute'" : "NULL")
        + ", "
        + (atStore ? CREATED + " + interval '2 minutes'" : "NULL")
        + ", "
        + (pickedUp ? CREATED + " + interval '3 minutes'" : "NULL")
        + ", "
        + (status.equals("DELIVERED") ? CREATED + " + interval '4 minutes'" : "NULL")
        + ", "
        + (status.equals("CANCELED") ? CREATED + " + interval '4 minutes'" : "NULL")
        + ")";
  }

  private static String offerInsert(
      UUID id, UUID order, UUID courier, String status, String respondedAt) {
    return "INSERT INTO offers (id, order_id, courier_id, status, eta_to_pickup_s, created_at, "
        + "expires_at, responded_at) VALUES ('"
        + id
        + "', '"
        + order
        + "', '"
        + courier
        + "', '"
        + status
        + "', 60, "
        + CREATED
        + ", "
        + CREATED
        + " + interval '30 seconds', "
        + (respondedAt == null ? "NULL" : respondedAt)
        + ")";
  }

  private static void insertEvent(Connection connection, UUID order, String type, long version)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO order_events (order_id, type, payload, actor, order_version) "
                + "VALUES (?, ?, '{}'::jsonb, 'test', ?)")) {
      statement.setObject(1, order);
      statement.setString(2, type);
      statement.setLong(3, version);
      statement.executeUpdate();
    }
  }

  private static int execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      return statement.executeUpdate(sql);
    }
  }

  private static long scalar(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getLong(1);
    }
  }

  private static void expectSqlError(
      Connection connection, String sql, String state, String constraint) {
    assertThatThrownBy(() -> execute(connection, sql))
        .isInstanceOfSatisfying(
            PSQLException.class,
            exception -> {
              assertThat(exception.getSQLState()).isEqualTo(state);
              if (constraint != null) {
                assertThat(exception.getServerErrorMessage()).isNotNull();
                assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
              }
            });
  }

  private record Violation(String name, Function<Fixture, String> sql, String constraint) {}

  private record Fixture(UUID courier, UUID order, UUID offer) {}

  @FunctionalInterface
  private interface FixtureAction {
    void run(Connection connection, Fixture fixture) throws Exception;
  }

  @FunctionalInterface
  private interface TransactionAction {
    String run(Connection connection) throws SQLException;
  }
}
