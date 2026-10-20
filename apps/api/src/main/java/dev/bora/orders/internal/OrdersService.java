package dev.bora.orders.internal;

import dev.bora.demo.DemoLimits;
import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.orders.InvalidOrderTransition;
import dev.bora.orders.OrderConfirmationCodes;
import dev.bora.orders.OrderEventView;
import dev.bora.orders.OrderPage;
import dev.bora.orders.OrderScope;
import dev.bora.orders.OrderSnapshots;
import dev.bora.orders.OrderSort;
import dev.bora.orders.OrderStatus;
import dev.bora.orders.OrderSummary;
import dev.bora.orders.OrderVersionConflict;
import dev.bora.orders.OrderView;
import dev.bora.orders.TrainingOrderReceipt;
import dev.bora.orders.TrainingOrderState;
import dev.bora.orders.TrainingOrders;
import dev.bora.routing.GeoPoint;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingService;
import dev.bora.shared.ApiException;
import dev.bora.stores.StoreDirectory;
import dev.bora.stores.StoreView;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class OrdersService implements OrderSnapshots, TrainingOrders {
  private static final String TRAINING_OPERATION = "COURIER_TRAINING_ORDER";
  private static final String OPERATION = "CREATE_ORDER";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final GeometryFactory GEOMETRY = new GeometryFactory(new PrecisionModel(), 4326);
  private static final String VISIBLE =
      """
      (o.visitor_context_id = :context OR
        (o.visitor_context_id IS NULL AND s.simulated = true))
      """;
  private static final String SELECT_ORDER =
      """
      SELECT o.id, o.public_code, o.status, o.courier_id, o.created_at, o.sla_deadline,
        o.version, o.owner_user_id, o.visitor_context_id, o.store_id,
        CASE WHEN o.status='SEARCHING' THEN GREATEST(o.created_at,COALESCE(
          (SELECT e.occurred_at FROM order_events e WHERE e.order_id=o.id
            AND e.type='ORDER_REASSIGNED' AND e.order_version<=o.version
            ORDER BY e.order_version DESC,e.id DESC LIMIT 1),o.created_at)) END searching_since,
        ST_Y(o.dropoff::geometry) dropoff_latitude, ST_X(o.dropoff::geometry) dropoff_longitude,
        s.name store_name, s.service_area_id,
        ST_Y(s.location::geometry) store_latitude, ST_X(s.location::geometry) store_longitude
      FROM orders o JOIN stores s ON s.id = o.store_id
      """;

  private final JdbcClient jdbc;
  private final EntityManager entityManager;
  private final Clock clock;
  private final StoreDirectory stores;
  private final RoutingService routing;
  private final DemoLimits limits;
  private final OrderConfirmationCodes codes;
  private final OrderLifecycleService lifecycle;
  private final JsonMapper mapper;
  private final TransactionTemplate transaction;
  private final TransactionTemplate readTransaction;

  OrdersService(
      JdbcClient jdbc,
      EntityManager entityManager,
      Clock clock,
      StoreDirectory stores,
      RoutingService routing,
      DemoLimits limits,
      OrderConfirmationCodes codes,
      OrderLifecycleService lifecycle,
      JsonMapper mapper,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.entityManager = entityManager;
    this.clock = clock;
    this.stores = stores;
    this.routing = routing;
    this.limits = limits;
    this.codes = codes;
    this.lifecycle = lifecycle;
    this.mapper = mapper;
    this.transaction = new TransactionTemplate(manager);
    this.readTransaction = new TransactionTemplate(manager);
    this.readTransaction.setReadOnly(true);
    this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  OrderView create(DemoPrincipal user, String key, CreateOrderRequest request) {
    requireStore(user);
    String keyHash = idempotencyKeyHash(key);
    GeoPoint dropoff;
    try {
      dropoff = new GeoPoint(request.dropoff().latitude(), request.dropoff().longitude());
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw failure(HttpStatus.BAD_REQUEST, "INVALID_DROPOFF", "Informe um destino válido.");
    }
    String requestHash =
        sha256(
            user.storeId()
                + ":"
                + canonical(dropoff.latitude())
                + ":"
                + canonical(dropoff.longitude()));
    OrderView previous = replay(user, keyHash, requestHash);
    if (previous != null) {
      return previous;
    }
    // O limitador usa uma conexão própria e deve terminar antes do lock transacional.
    limits.checkOrderCreation(user.contextId());
    StoreView store = stores.find(user.storeId());
    boolean publicSimulation = !user.visitor();
    if (publicSimulation
        && !jdbc.sql("SELECT simulated FROM stores WHERE id=:id")
            .param("id", store.id())
            .query(Boolean.class)
            .single()) {
      throw failure(
          HttpStatus.FORBIDDEN,
          "SIMULATOR_STORE_REQUIRED",
          "A identidade interna exige uma loja simulada.");
    }
    validateDropoff(store, dropoff);
    return transaction.execute(
        status -> {
          lockContext(user.contextId());
          OrderView repeated = replay(user, keyHash, requestHash);
          if (repeated != null) {
            return repeated;
          }
          UUID id = persistNewOrder(user, store, dropoff, publicSimulation, clock.instant());
          OrderView created = detail(user, id);
          ObjectNode snapshot = mapper.valueToTree(created);
          snapshot.remove("confirmationCode");
          rememberRequest(user, OPERATION, keyHash, requestHash, id, snapshot);
          return created;
        });
  }

  @Override
  public TrainingOrderReceipt replayTraining(DemoPrincipal user, String key) {
    requireTrainingCourier(user);
    String keyHash = idempotencyKeyHash(key);
    var existing =
        jdbc.sql(
                """
            SELECT r.request_hash,r.response_json::text response,o.owner_user_id,o.status,o.version
            FROM order_idempotency r JOIN orders o ON o.id=r.order_id
            JOIN demo_contexts dc ON dc.id=r.context_id
            WHERE r.context_id=:context AND r.operation=:operation AND r.key_hash=:key
              AND o.visitor_context_id=:context
              AND dc.expires_at>dc.created_at AND dc.expires_at>clock_timestamp()
            """)
            .param("context", user.contextId())
            .param("operation", TRAINING_OPERATION)
            .param("key", keyHash)
            .query(
                (row, index) ->
                    new SavedTraining(
                        row.getString("request_hash"),
                        row.getString("response"),
                        row.getObject("owner_user_id", UUID.class),
                        OrderStatus.valueOf(row.getString("status")),
                        row.getLong("version")))
            .optional();
    if (existing.isEmpty()) return null;
    var saved = existing.get();
    if (!user.userId().equals(saved.owner())
        || !trainingRequestHash(user).equals(saved.requestHash())) {
      throw failure(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "Esta chave de idempotência já foi usada em outra solicitação.");
    }
    var value = mapper.readTree(saved.response());
    UUID orderId = UUID.fromString(value.path("orderId").asString());
    return new TrainingOrderReceipt(
        orderId,
        UUID.fromString(value.path("offerId").asString()),
        Instant.parse(value.path("expiresAt").asString()),
        codes.code(orderId),
        saved.status(),
        saved.version(),
        clock.instant());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public TrainingOrderReceipt lockAndReplayTraining(DemoPrincipal user, String key) {
    requireTrainingCourier(user);
    idempotencyKeyHash(key);
    lockLiveTrainingContext(user);
    return replayTraining(user, key);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public TrainingOrderReceipt createTraining(
      DemoPrincipal user, String key, StoreView store, GeoPoint dropoff, UUID offerId) {
    requireTrainingCourier(user);
    String keyHash = idempotencyKeyHash(key);
    validateDropoff(store, dropoff);
    lockLiveTrainingContext(user);
    Instant now = clock.instant();
    UUID id = persistNewOrder(user, store, dropoff, false, now);
    Instant expiresAt = now.plusSeconds(30);
    ObjectNode saved = mapper.createObjectNode();
    saved.put("orderId", id.toString());
    saved.put("offerId", offerId.toString());
    saved.put("expiresAt", expiresAt.toString());
    rememberRequest(user, TRAINING_OPERATION, keyHash, trainingRequestHash(user), id, saved);
    return new TrainingOrderReceipt(
        id, offerId, expiresAt, codes.code(id), OrderStatus.SEARCHING, 0, now);
  }

  @Override
  public TrainingOrderState cancelTraining(DemoPrincipal user, UUID orderId, long expectedVersion) {
    requireTrainingCourier(user);
    if (expectedVersion < 0) {
      throw failure(
          HttpStatus.BAD_REQUEST, "INVALID_ORDER_VERSION", "Informe a versão atual do treino.");
    }
    try {
      return transaction.execute(
          ignored -> {
            lockLiveTrainingContext(user);
            var current =
                jdbc.sql(
                        """
            SELECT o.version FROM orders o JOIN order_idempotency r ON r.order_id=o.id
            WHERE o.id=:id AND o.visitor_context_id=:context AND o.owner_user_id=:owner
              AND r.context_id=:context AND r.operation=:operation
            FOR UPDATE OF o
            """)
                    .param("id", orderId)
                    .param("context", user.contextId())
                    .param("owner", user.userId())
                    .param("operation", TRAINING_OPERATION)
                    .query(Long.class)
                    .optional();
            if (current.isEmpty()) {
              throw failure(
                  HttpStatus.NOT_FOUND,
                  "TRAINING_ORDER_NOT_FOUND",
                  "Pedido de treino não encontrado.");
            }
            if (current.get() != expectedVersion) throw new OrderVersionConflict(orderId);
            // Reutiliza a transição normal. Ela libera a oferta e o courier pelo listener
            // transacional existente, e continua recusando cancelamento após a coleta.
            var change =
                lifecycle.cancelByOperations(orderId, expectedVersion, "user:" + user.userId());
            return new TrainingOrderState(
                change.orderId(), change.status(), change.version(), clock.instant());
          });
    } catch (OrderVersionConflict changed) {
      throw failure(
          HttpStatus.CONFLICT,
          "ORDER_VERSION_CONFLICT",
          "O treino foi atualizado. Consulte o estado atual e tente novamente.");
    } catch (InvalidOrderTransition unavailable) {
      throw failure(
          HttpStatus.CONFLICT,
          "INVALID_ORDER_TRANSITION",
          "Não é possível encerrar o treino nesta etapa. Conclua a entrega após a coleta.");
    }
  }

  private void lockLiveTrainingContext(DemoPrincipal user) {
    lockContext(user.contextId());
    // The context can expire while this request waits for another owner transaction.
    if (!jdbc.sql(
            """
        SELECT EXISTS(SELECT 1 FROM demo_contexts WHERE id=:context
          AND expires_at>created_at AND expires_at>clock_timestamp())
        """)
        .param("context", user.contextId())
        .query(Boolean.class)
        .single()) {
      throw failure(
          HttpStatus.UNAUTHORIZED,
          "DEMO_EXPIRED",
          "Sua demonstração expirou. Entre novamente para continuar.");
    }
  }

  private static String trainingRequestHash(DemoPrincipal user) {
    return sha256("courier-training:v1:" + user.userId() + ":" + user.courierId());
  }

  private static void requireTrainingCourier(DemoPrincipal user) {
    if (user.role() != DemoRole.COURIER || user.courierId() == null || !user.visitor()) {
      throw failure(
          HttpStatus.FORBIDDEN,
          "TRAINING_COURIER_REQUIRED",
          "Entre como entregador visitante para criar um pedido de treino.");
    }
  }

  private static String idempotencyKeyHash(String key) {
    if (key == null || !key.matches("[\\x21-\\x7E]{1,128}")) {
      throw failure(
          HttpStatus.BAD_REQUEST,
          "INVALID_IDEMPOTENCY_KEY",
          "Informe uma chave de idempotência de 1 a 128 caracteres ASCII sem espaços.");
    }
    return sha256(key);
  }

  private void validateDropoff(StoreView store, GeoPoint dropoff) {
    if (!stores.covers(store.serviceAreaId(), dropoff)) {
      throw failure(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "OUTSIDE_SERVICE_AREA",
          "Escolha um destino dentro da área atendida.");
    }
    try {
      var route = routing.route(store.location(), dropoff);
      if (!stores.covers(store.serviceAreaId(), route.destination().matched())) {
        throw failure(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "OUTSIDE_SERVICE_AREA",
            "O acesso pela via deve ficar dentro da área atendida.");
      }
    } catch (RoutingException exception) {
      if (exception.reason() == RoutingException.Reason.GRAPH_UNAVAILABLE) {
        throw failure(
            HttpStatus.SERVICE_UNAVAILABLE,
            "ROUTING_UNAVAILABLE",
            "O roteamento ainda está sendo preparado. Tente novamente em instantes.");
      }
      throw failure(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "DROPOFF_UNREACHABLE",
          "Não encontramos uma rota de moto para esse destino. Escolha um ponto próximo a uma via.");
    }
  }

  private UUID persistNewOrder(
      DemoPrincipal user,
      StoreView store,
      GeoPoint dropoff,
      boolean publicSimulation,
      Instant now) {
    long active =
        jdbc.sql(
                """
        SELECT count(*) FROM orders WHERE visitor_context_id = :context
          AND status IN ('SEARCHING', 'ASSIGNED', 'AT_STORE', 'PICKED_UP')
        """)
            .param("context", user.contextId())
            .query(Long.class)
            .single();
    if (user.visitor() && active >= 5) {
      throw failure(
          HttpStatus.TOO_MANY_REQUESTS,
          "ACTIVE_ORDER_LIMIT",
          "Você já tem cinco pedidos ativos. Conclua ou cancele um deles antes de criar outro.");
    }
    UUID id = UUID.randomUUID();
    entityManager.persist(
        new OrderEntity(
            id,
            publicCode(),
            store.id(),
            GEOMETRY.createPoint(new Coordinate(dropoff.longitude(), dropoff.latitude())),
            publicSimulation ? null : codes.hash(id),
            now,
            now.plusSeconds(1_800),
            publicSimulation ? null : user.contextId(),
            publicSimulation ? null : user.userId()));
    entityManager.flush();
    entityManager.persist(
        new OrderEventEntity(
            id,
            "ORDER_CREATED",
            Map.of("to", OrderStatus.SEARCHING.name(), "version", 0),
            "user:" + user.userId(),
            now,
            0));
    entityManager.flush();
    return id;
  }

  private void rememberRequest(
      DemoPrincipal user,
      String operation,
      String keyHash,
      String requestHash,
      UUID id,
      ObjectNode snapshot) {
    jdbc.sql(
            """
        INSERT INTO order_idempotency
          (context_id, operation, key_hash, request_hash, order_id, response_json)
        VALUES (:context, :operation, :key, :request, :id, CAST(:response AS jsonb))
        """)
        .param("context", user.contextId())
        .param("operation", operation)
        .param("key", keyHash)
        .param("request", requestHash)
        .param("id", id)
        .param("response", mapper.writeValueAsString(snapshot))
        .update();
  }

  OrderPage list(
      DemoPrincipal user,
      int page,
      int size,
      OrderStatus status,
      String code,
      OrderScope scope,
      Instant snapshotAt,
      OrderSort sort) {
    requireReader(user);
    if (page < 0 || size < 1 || size > 100 || page > 100_000) {
      throw failure(
          HttpStatus.BAD_REQUEST,
          "INVALID_PAGE",
          "Informe uma página entre 0 e 100000 e um tamanho entre 1 e 100.");
    }
    if (sort == OrderSort.WAITING && user.role() != DemoRole.OPERATIONS) {
      throw failure(
          HttpStatus.FORBIDDEN,
          "OPERATIONS_ROLE_REQUIRED",
          "Entre na operação para consultar a fila por tempo de espera.");
    }
    if (sort == OrderSort.WAITING && status != OrderStatus.SEARCHING) {
      throw failure(
          HttpStatus.BAD_REQUEST,
          "INVALID_ORDER_SORT",
          "A fila por tempo de espera exige o estado de busca por entregador.");
    }
    String ordering =
        sort == OrderSort.WAITING
            ? " ORDER BY searching_since ASC,o.created_at ASC,o.id ASC"
            : " ORDER BY o.created_at DESC,o.id DESC";
    String queryCode = code == null || code.isBlank() ? null : code.strip();
    if (queryCode != null && !queryCode.matches("[A-Za-z0-9_-]{20,64}")) {
      throw failure(
          HttpStatus.BAD_REQUEST,
          "INVALID_ORDER_CODE",
          "Cole o código público completo do pedido, sem usar o código de confirmação da entrega.");
    }
    return readTransaction.execute(
        ignored -> {
          Instant now =
              jdbc.sql("SELECT clock_timestamp()")
                  .query((row, index) -> row.getTimestamp(1).toInstant())
                  .single();
          if (snapshotAt != null && snapshotAt.isAfter(now)) {
            throw failure(
                HttpStatus.BAD_REQUEST,
                "INVALID_ORDER_SNAPSHOT",
                "Use o instante de consulta retornado pela listagem, sem uma data futura.");
          }
          Instant boundary = snapshotAt == null ? now : snapshotAt;
          String where =
              " WHERE "
                  + VISIBLE
                  + " AND o.created_at<=:snapshot"
                  + " AND (:scope='ALL' OR (:scope='OWN' AND o.visitor_context_id=:context)"
                  + " OR (:scope='PUBLIC' AND o.visitor_context_id IS NULL AND s.simulated))"
                  + " AND (CAST(:status AS varchar) IS NULL OR o.status=:status)"
                  + " AND (CAST(:code AS varchar) IS NULL OR o.public_code=:code)";
          var parameters = new java.util.HashMap<String, Object>();
          parameters.put("context", user.contextId());
          parameters.put("scope", scope.name());
          parameters.put("snapshot", java.sql.Timestamp.from(boundary));
          parameters.put("status", status == null ? null : status.name());
          parameters.put("code", queryCode);
          long total =
              jdbc.sql("SELECT count(*) FROM orders o JOIN stores s ON s.id=o.store_id" + where)
                  .params(parameters)
                  .query(Long.class)
                  .single();
          List<OrderSummary> items =
              jdbc.sql(SELECT_ORDER + where + ordering + " LIMIT :size OFFSET :offset")
                  .params(parameters)
                  .param("size", size)
                  .param("offset", (long) page * size)
                  .query((row, index) -> summary(row))
                  .list();
          return new OrderPage(
              items, page, size, total, (int) ((total + size - 1) / size), boundary);
        });
  }

  OrderView detail(DemoPrincipal user, UUID id) {
    requireReader(user);
    OrderRecord record = findVisible(user, id);
    return view(user, record);
  }

  @Override
  public OrderView snapshot(DemoPrincipal user, UUID id) {
    if (user.role() != DemoRole.COURIER) {
      return detail(user, id);
    }
    OrderRecord record =
        jdbc.sql(SELECT_ORDER + " WHERE o.id=:id AND o.courier_id=:courier")
            .param("id", id)
            .param("courier", user.courierId())
            .query(
                (row, index) ->
                    new OrderRecord(
                        summary(row),
                        row.getObject("owner_user_id", UUID.class),
                        row.getObject("visitor_context_id", UUID.class)))
            .optional()
            .orElseThrow(OrdersService::notFound);
    return view(user, record);
  }

  private OrderView view(DemoPrincipal user, OrderRecord record) {
    OrderSummary order = record.summary();
    UUID id = order.id();
    List<OrderEventView> events =
        jdbc.sql(
                """
            SELECT id, type, payload->>'from' from_status, payload->>'to' to_status,
              occurred_at, order_version
            FROM order_events WHERE order_id = :id AND order_version <= :version
            ORDER BY occurred_at, id
            """)
            .param("id", id)
            .param("version", order.version())
            .query(
                (row, index) ->
                    new OrderEventView(
                        row.getLong("id"),
                        row.getString("type"),
                        orderStatus(row.getString("from_status")),
                        orderStatus(row.getString("to_status")),
                        instant(row, "occurred_at"),
                        row.getLong("order_version")))
            .list();
    String code =
        user.visitor()
                && user.role() == DemoRole.STORE
                && user.userId().equals(record.ownerUserId())
            ? codes.code(id)
            : null;
    return new OrderView(
        order.id(),
        order.publicCode(),
        order.store(),
        order.dropoff(),
        order.status(),
        order.courierId(),
        order.createdAt(),
        order.slaDeadline(),
        order.version(),
        code,
        events);
  }

  OrderView cancel(DemoPrincipal user, UUID id, long expectedVersion) {
    requireReader(user);
    try {
      return transaction.execute(
          status -> {
            OrderRecord record = findVisible(user, id);
            if (user.role() == DemoRole.STORE
                && (!(user.visitor()
                        ? user.contextId().equals(record.contextId())
                        : record.contextId() == null)
                    || !record.summary().store().id().equals(user.storeId()))) {
              throw notFound();
            }
            if (user.role() == DemoRole.STORE) {
              lifecycle.cancelByStore(id, expectedVersion, "user:" + user.userId());
            } else {
              lifecycle.cancelByOperations(id, expectedVersion, "user:" + user.userId());
            }
            return detail(user, id);
          });
    } catch (OrderVersionConflict exception) {
      throw failure(
          HttpStatus.CONFLICT,
          "ORDER_VERSION_CONFLICT",
          "O pedido foi atualizado. Consulte a versão atual e tente novamente.");
    } catch (InvalidOrderTransition exception) {
      throw failure(
          HttpStatus.CONFLICT,
          "INVALID_ORDER_TRANSITION",
          "Não é possível cancelar o pedido nesta etapa.");
    }
  }

  private OrderRecord findVisible(DemoPrincipal user, UUID id) {
    return jdbc.sql(SELECT_ORDER + " WHERE o.id=:id AND " + VISIBLE)
        .param("id", id)
        .param("context", user.contextId())
        .query(
            (row, index) ->
                new OrderRecord(
                    summary(row),
                    row.getObject("owner_user_id", UUID.class),
                    row.getObject("visitor_context_id", UUID.class)))
        .optional()
        .orElseThrow(OrdersService::notFound);
  }

  private OrderView replay(DemoPrincipal user, String key, String request) {
    var existing =
        jdbc.sql(
                """
            SELECT request_hash, response_json::text response FROM order_idempotency
            WHERE context_id=:context AND operation=:operation AND key_hash=:key
            """)
            .param("context", user.contextId())
            .param("operation", OPERATION)
            .param("key", key)
            .query(
                (row, index) ->
                    new SavedResponse(row.getString("request_hash"), row.getString("response")))
            .optional();
    if (existing.isEmpty()) {
      return null;
    }
    if (!existing.get().requestHash().equals(request)) {
      throw failure(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "Esta chave de idempotência já foi usada com outro destino.");
    }
    OrderView saved = mapper.readValue(existing.get().response(), OrderView.class);
    return saved.withConfirmationCode(user.visitor() ? codes.code(saved.id()) : null);
  }

  private void lockContext(UUID context) {
    var locked =
        jdbc.sql(
                """
            SELECT id FROM demo_contexts WHERE id=:id AND expires_at>created_at AND expires_at>CURRENT_TIMESTAMP FOR UPDATE
            """)
            .param("id", context)
            .query(UUID.class)
            .optional();
    if (locked.isEmpty()) {
      throw failure(
          HttpStatus.UNAUTHORIZED,
          "DEMO_EXPIRED",
          "Sua demonstração expirou. Entre novamente para continuar.");
    }
  }

  private static OrderSummary summary(ResultSet row) throws SQLException {
    return new OrderSummary(
        row.getObject("id", UUID.class),
        row.getString("public_code"),
        new StoreView(
            row.getObject("store_id", UUID.class),
            row.getString("store_name"),
            new GeoPoint(row.getDouble("store_latitude"), row.getDouble("store_longitude")),
            row.getObject("service_area_id", UUID.class)),
        new GeoPoint(row.getDouble("dropoff_latitude"), row.getDouble("dropoff_longitude")),
        OrderStatus.valueOf(row.getString("status")),
        row.getObject("courier_id", UUID.class),
        instant(row, "created_at"),
        instant(row, "sla_deadline"),
        row.getLong("version"),
        row.getTimestamp("searching_since") == null ? null : instant(row, "searching_since"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    return row.getTimestamp(column).toInstant();
  }

  private static OrderStatus orderStatus(String value) {
    return value == null ? null : OrderStatus.valueOf(value);
  }

  private static void requireStore(DemoPrincipal user) {
    if (user.role() != DemoRole.STORE || user.storeId() == null) {
      throw failure(
          HttpStatus.FORBIDDEN, "STORE_ROLE_REQUIRED", "Entre como loja para criar pedidos.");
    }
  }

  private static void requireReader(DemoPrincipal user) {
    if (user.role() != DemoRole.STORE && user.role() != DemoRole.OPERATIONS) {
      throw failure(
          HttpStatus.FORBIDDEN,
          "ORDER_ROLE_REQUIRED",
          "Entre como loja ou operação para consultar pedidos.");
    }
  }

  private static String canonical(double coordinate) {
    return Double.toString(coordinate == 0 ? 0 : coordinate);
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(
          "Não foi possível calcular a identidade da requisição.", exception);
    }
  }

  private static String publicCode() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static ApiException notFound() {
    return failure(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Pedido não encontrado.");
  }

  private static ApiException failure(HttpStatus status, String code, String message) {
    return new ApiException(status, code, message);
  }

  private record SavedResponse(String requestHash, String response) {}

  private record SavedTraining(
      String requestHash, String response, UUID owner, OrderStatus status, long version) {}

  private record OrderRecord(OrderSummary summary, UUID ownerUserId, UUID contextId) {}
}
