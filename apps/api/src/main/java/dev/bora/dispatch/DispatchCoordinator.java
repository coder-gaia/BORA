package dev.bora.dispatch;

import dev.bora.couriers.CourierCommand;
import dev.bora.couriers.CourierDirectory;
import dev.bora.couriers.CourierStatus;
import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.orders.InvalidOrderTransition;
import dev.bora.orders.OrderStatus;
import dev.bora.orders.OrderVersionConflict;
import dev.bora.orders.OrderWorkItem;
import dev.bora.orders.OrderWorkflow;
import dev.bora.routing.GeoPoint;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingService;
import dev.bora.shared.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DispatchCoordinator {
  private static final Logger log = LoggerFactory.getLogger(DispatchCoordinator.class);
  private static final String ACTIVE_ORDER =
      """
      ((o.visitor_context_id IS NULL AND s.simulated) OR EXISTS (
        SELECT 1 FROM demo_contexts dc WHERE dc.id=o.visitor_context_id
          AND dc.expires_at>dc.created_at AND dc.expires_at>:now))
      """;
  private static final String ELIGIBLE_COURIER =
      """
      c.status='AVAILABLE' AND c.location_updated_at>=:fresh AND c.location_updated_at<=:future
      AND ((CAST(:context AS uuid) IS NULL AND c.simulated) OR (NOT c.simulated AND EXISTS (
        SELECT 1 FROM users u JOIN demo_contexts dc ON dc.id=u.context_id
        WHERE u.courier_id=c.id AND (CAST(:context AS uuid) IS NULL OR u.context_id=:context)
          AND u.role='COURIER' AND u.visitor
          AND u.expires_at>:now AND dc.expires_at>dc.created_at AND dc.expires_at>:now
          AND (CAST(:context AS uuid) IS NOT NULL OR NOT EXISTS (
            SELECT 1 FROM orders private_search WHERE private_search.visitor_context_id=u.context_id
              AND private_search.status='SEARCHING')))))
      AND NOT EXISTS (SELECT 1 FROM offers history
        WHERE history.order_id=:order AND history.courier_id=c.id
          AND history.status IN ('ACCEPTED','REJECTED','EXPIRED'))
      """;
  private final JdbcClient jdbc;
  private final OrderWorkflow orders;
  private final CourierDirectory couriers;
  private final RoutingService routing;
  private final PendingOfferCleanup cleanup;
  private final OfferAudit audit;
  private final DispatchActivation activation;
  private final JsonMapper mapper;
  private final Clock clock;
  private final TransactionTemplate transaction;
  private final TransactionTemplate readTransaction;

  public DispatchCoordinator(
      JdbcClient jdbc,
      OrderWorkflow orders,
      CourierDirectory couriers,
      RoutingService routing,
      PendingOfferCleanup cleanup,
      OfferAudit audit,
      DispatchActivation activation,
      JsonMapper mapper,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.orders = orders;
    this.couriers = couriers;
    this.routing = routing;
    this.cleanup = cleanup;
    this.audit = audit;
    this.activation = activation;
    this.mapper = mapper;
    this.clock = clock;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readTransaction = new TransactionTemplate(manager);
    readTransaction.setReadOnly(true);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  /** Processa até vinte pedidos, sem manter reservas de pedidos diferentes na mesma transação. */
  public int dispatchAvailableOrders() {
    int created = 0;
    for (int attempt = 0; attempt < 20 && activation.allowsCreation(); attempt++) {
      int outcome = transaction.execute(status -> dispatchOne());
      if (outcome < 0) break;
      created += outcome;
    }
    return created;
  }

  private int dispatchOne() {
    if (!activation.allowsCreation()) return -1;
    Instant now = clock.instant();
    var due =
        jdbc.sql(
                """
        SELECT o.id FROM orders o JOIN stores s ON s.id=o.store_id
        WHERE o.status='SEARCHING' AND o.next_attempt_at<=:now AND
        """
                    + ACTIVE_ORDER
                    + """
        AND NOT EXISTS (SELECT 1 FROM offers f WHERE f.order_id=o.id AND f.status='PENDING')
        ORDER BY o.next_attempt_at,o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
        """)
            .param("now", timestamp(now))
            .query(UUID.class)
            .optional();
    if (due.isEmpty()) return -1;
    OrderWorkItem order = orders.lockForUpdate(due.get());
    int radius =
        jdbc.sql("SELECT search_radius_m FROM orders WHERE id=:id")
            .param("id", order.id())
            .query(Integer.class)
            .single();
    if (!now.isBefore(order.createdAt().plusSeconds(180))
        && jdbc.sql(
                    "SELECT count(*) FROM order_events WHERE order_id=:id AND type='ORDER_SEARCH_DELAYED'")
                .param("id", order.id())
                .query(Long.class)
                .single()
            == 0) {
      audit.write(order.id(), "ORDER_SEARCH_DELAYED", null, null, null, null, "dispatch", now);
    }
    List<Candidate> candidates =
        eligible(
                jdbc.sql(
                    """
        SELECT c.id,c.position_epoch,c.location_updated_at,
          ST_Y(c.location::geometry) latitude,ST_X(c.location::geometry) longitude
        FROM couriers c WHERE
        """
                        + ELIGIBLE_COURIER
                        + """
        AND ST_DWithin(c.location,ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,:radius)
        ORDER BY c.location <-> ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,c.id LIMIT 8
        """),
                order,
                now)
            .param("lon", order.store().location().longitude())
            .param("lat", order.store().location().latitude())
            .param("radius", radius)
            .query(
                (row, index) ->
                    new Candidate(
                        row.getObject("id", UUID.class),
                        row.getLong("position_epoch"),
                        row.getTimestamp("location_updated_at").toInstant(),
                        new GeoPoint(row.getDouble("latitude"), row.getDouble("longitude")),
                        0))
            .list();
    var ranked = new ArrayList<Candidate>();
    for (Candidate candidate : candidates) {
      try {
        long millis =
            routing
                .route(candidate.location(), order.store().location())
                .simulatedDuration()
                .toMillis();
        ranked.add(
            new Candidate(
                candidate.id(),
                candidate.epoch(),
                candidate.recordedAt(),
                candidate.location(),
                millis));
      } catch (RoutingException unavailable) {
        log.debug("Candidato sem rota para o pedido {}: {}", order.id(), unavailable.reason());
      }
    }
    ranked.sort(
        Comparator.comparingLong(Candidate::durationMillis).thenComparing(c -> c.id().toString()));
    now = clock.instant();
    if (!activeOrder(order.id(), now) || !activation.allowsCreation()) return -1;
    if (ranked.isEmpty()) {
      retry(order.id(), now, true);
      return 0;
    }
    var rows = new ArrayList<RankedRow>();
    for (int rank = 0; rank < ranked.size(); rank++) {
      Candidate c = ranked.get(rank);
      rows.add(new RankedRow(c.id(), rank, c.recordedAt().toString(), c.epoch()));
    }
    // LIMIT 1 evita manter vários locks de entregadores fora da ordem usada pela gravação em lote.
    var selected =
        eligible(
                jdbc.sql(
                    """
        SELECT c.id,c.simulated FROM jsonb_to_recordset(CAST(:ranked AS jsonb))
          AS r(id uuid,rank integer,recorded timestamptz,epoch bigint)
        JOIN couriers c ON c.id=r.id
        WHERE c.position_epoch=r.epoch AND c.location_updated_at=r.recorded AND
        """
                        + ELIGIBLE_COURIER
                        + """
        ORDER BY r.rank LIMIT 1 FOR UPDATE OF c SKIP LOCKED
        """),
                order,
                now)
            .param("ranked", mapper.writeValueAsString(rows))
            .query(
                (row, index) ->
                    new SelectedCourier(
                        row.getObject("id", UUID.class), row.getBoolean("simulated")))
            .optional();
    if (selected.isEmpty()) {
      retry(order.id(), now, false);
      return 0;
    }
    now = clock.instant();
    if (!activation.allowsCreation() || !activeOrder(order.id(), now)) return -1;
    SelectedCourier selectedCourier = selected.get();
    UUID courierId = selectedCourier.id();
    if (order.visitorContextId() == null && !publicReservationAllowed(selectedCourier)) {
      retry(order.id(), clock.instant(), false);
      return 0;
    }
    long durationMillis =
        ranked.stream()
            .filter(c -> c.id().equals(courierId))
            .findFirst()
            .orElseThrow()
            .durationMillis();
    int eta =
        Math.toIntExact(
            Math.min(
                Integer.MAX_VALUE, durationMillis / 1000 + (durationMillis % 1000 == 0 ? 0 : 1)));
    couriers.transition(courierId, new CourierCommand.ReceiveOffer());
    UUID offer = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO offers(id,order_id,courier_id,status,eta_to_pickup_s,created_at,expires_at)
        VALUES(:id,:order,:courier,'PENDING',:eta,:now,:expires)
        """)
        .param("id", offer)
        .param("order", order.id())
        .param("courier", courierId)
        .param("eta", eta)
        .param("now", timestamp(now))
        .param("expires", timestamp(now.plusSeconds(30)))
        .update();
    jdbc.sql("UPDATE orders SET next_attempt_at=:next WHERE id=:id")
        .param("id", order.id())
        .param("next", timestamp(now.plusSeconds(30)))
        .update();
    audit.write(
        order.id(), "OFFER_CREATED", offer, courierId, null, OfferStatus.PENDING, "dispatch", now);
    return 1;
  }

  private JdbcClient.StatementSpec eligible(
      JdbcClient.StatementSpec query, OrderWorkItem order, Instant now) {
    return query
        .param("context", order.visitorContextId())
        .param("order", order.id())
        .param("now", timestamp(now))
        .param("fresh", timestamp(now.minusSeconds(30)))
        .param("future", timestamp(now.plusSeconds(5)));
  }

  private boolean publicReservationAllowed(SelectedCourier courier) {
    if (courier.simulated()) return true;
    var context =
        jdbc.sql(
                """
        SELECT dc.id FROM users u JOIN demo_contexts dc ON dc.id=u.context_id
        WHERE u.courier_id=:courier AND u.role='COURIER' AND u.visitor
        FOR UPDATE OF dc SKIP LOCKED
        """)
            .param("courier", courier.id())
            .query(UUID.class)
            .optional();
    if (context.isEmpty()) return false;
    // A criação privada usa este mesmo lock de contexto. Se ele estiver ocupado, a reserva
    // é adiada; após adquiri-lo, uma consulta nova enxerga qualquer criação já confirmada.
    return jdbc.sql(
            """
        SELECT EXISTS (
          SELECT 1 FROM users u JOIN demo_contexts dc ON dc.id=u.context_id
          WHERE u.courier_id=:courier AND u.context_id=:context AND u.role='COURIER' AND u.visitor
            AND u.expires_at>:now AND dc.expires_at>dc.created_at AND dc.expires_at>:now
        ) AND NOT EXISTS (
          SELECT 1 FROM orders WHERE visitor_context_id=:context AND status='SEARCHING'
        )
        """)
        .param("courier", courier.id())
        .param("context", context.get())
        .param("now", timestamp(clock.instant()))
        .query(Boolean.class)
        .single();
  }

  private boolean activeOrder(UUID id, Instant now) {
    return jdbc.sql(
                "SELECT count(*) FROM orders o JOIN stores s ON s.id=o.store_id WHERE o.id=:id AND "
                    + ACTIVE_ORDER)
            .param("id", id)
            .param("now", timestamp(now))
            .query(Long.class)
            .single()
        == 1;
  }

  private void retry(UUID order, Instant now, boolean expand) {
    jdbc.sql(
            """
        UPDATE orders SET next_attempt_at=GREATEST(created_at,:next),
          search_radius_m=CASE WHEN :expand THEN LEAST(8000,search_radius_m*2) ELSE search_radius_m END
        WHERE id=:id AND status='SEARCHING'
        """)
        .param("id", order)
        .param("next", timestamp(now.plusSeconds(5)))
        .param("expand", expand)
        .update();
  }

  public CourierOfferInbox inbox(DemoPrincipal user) {
    requireCourier(user);
    return readTransaction.execute(
        status -> {
          Instant now = clock.instant();
          var pending =
              jdbc.sql(
                      """
          SELECT f.id FROM offers f JOIN orders o ON o.id=f.order_id JOIN stores s ON s.id=o.store_id
          JOIN couriers c ON c.id=f.courier_id
          WHERE f.courier_id=:courier AND f.status='PENDING' AND f.expires_at>:now
            AND o.status='SEARCHING'
            AND ((o.visitor_context_id IS NULL AND s.simulated)
              OR (NOT c.simulated AND o.visitor_context_id=:context)) AND
          """
                          + ACTIVE_ORDER)
                  .param("courier", user.courierId())
                  .param("context", user.contextId())
                  .param("now", timestamp(now))
                  .query(UUID.class)
                  .optional();
          return new CourierOfferInbox(pending.map(this::view).orElse(null), now);
        });
  }

  private CourierOffer view(UUID id) {
    OfferRow offer = readOffer(id, false);
    OrderWorkItem order = orders.find(offer.orderId()).orElseThrow();
    return new CourierOffer(
        offer.id(),
        order.id(),
        order.store(),
        order.dropoff(),
        offer.eta(),
        offer.createdAt(),
        offer.expiresAt());
  }

  public void accept(DemoPrincipal user, UUID offer) {
    decide(user, offer, true);
  }

  public void reject(DemoPrincipal user, UUID offer) {
    decide(user, offer, false);
  }

  private void decide(DemoPrincipal user, UUID id, boolean accept) {
    requireCourier(user);
    boolean expired =
        transaction.execute(
            status -> {
              var owner =
                  jdbc.sql("SELECT order_id FROM offers WHERE id=:id AND courier_id=:courier")
                      .param("id", id)
                      .param("courier", user.courierId())
                      .query(UUID.class)
                      .optional();
              if (owner.isEmpty()) throw missing();
              OrderWorkItem order = orders.lockForUpdate(owner.get());
              OfferRow offer = readOffer(id, true);
              if (!offer.courierId().equals(user.courierId())) throw missing();
              var courier = couriers.lock(offer.courierId());
              Instant now = clock.instant();
              boolean simulated =
                  jdbc.sql("SELECT simulated FROM couriers WHERE id=:id")
                      .param("id", courier.id())
                      .query(Boolean.class)
                      .single();
              if ((order.visitorContextId() != null
                      && (simulated || !user.contextId().equals(order.visitorContextId())))
                  || !activeOrder(order.id(), now)) throw missing();
              if (offer.status() != OfferStatus.PENDING)
                throw conflict("OFFER_CLOSED", "Esta oferta já foi encerrada.");
              if (!now.isBefore(offer.expiresAt())) {
                cleanup.expirePendingForLockedOrder(order.id(), now);
                return true;
              }
              if (order.status() != OrderStatus.SEARCHING
                  || courier.status() != CourierStatus.OFFERED)
                throw conflict("OFFER_UNAVAILABLE", "Esta oferta não está mais disponível.");
              OfferStatus next =
                  OfferState.restore(offer.status(), offer.expiresAt())
                      .apply(accept ? new OfferCommand.Accept() : new OfferCommand.Reject(), now)
                      .next()
                      .status();
              jdbc.sql(
                      "UPDATE offers SET status=:status,responded_at=:now WHERE id=:id AND status='PENDING'")
                  .param("id", id)
                  .param("status", next.name())
                  .param("now", timestamp(now))
                  .update();
              couriers.transition(
                  courier.id(),
                  accept ? new CourierCommand.AcceptOffer() : new CourierCommand.RejectOffer());
              if (accept)
                orders.assign(order.id(), order.version(), courier.id(), user.userId().toString());
              else scheduleNow(order.id(), now);
              audit.write(
                  order.id(),
                  accept ? "OFFER_ACCEPTED" : "OFFER_REJECTED",
                  id,
                  courier.id(),
                  OfferStatus.PENDING,
                  next,
                  user.userId().toString(),
                  now);
              return false;
            });
    // O prazo encerrado é persistido antes de comunicar o conflito ao cliente.
    if (expired)
      throw conflict("OFFER_EXPIRED", "O prazo desta oferta terminou. Aguarde uma nova oferta.");
  }

  public void reassign(DemoPrincipal user, UUID id, long expectedVersion) {
    if (user.role() != DemoRole.OPERATIONS) throw forbidden();
    try {
      transaction.executeWithoutResult(
          status -> {
            OrderWorkItem order = orders.lockForUpdate(id);
            if (!user.contextId().equals(order.visitorContextId()) && !order.simulated())
              throw missing();
            if (expectedVersion != order.version()) throw new OrderVersionConflict(id);
            orders.reassign(id, expectedVersion, user.userId().toString());
          });
    } catch (OrderVersionConflict | InvalidOrderTransition unavailable) {
      throw conflict(
          "ORDER_CONFLICT", "O pedido mudou ou não permite redistribuição. Atualize a tela.");
    }
  }

  private OfferRow readOffer(UUID id, boolean lock) {
    return jdbc.sql(
            """
        SELECT id,order_id,courier_id,status,eta_to_pickup_s,created_at,expires_at FROM offers WHERE id=:id
        """
                + (lock ? " FOR UPDATE" : ""))
        .param("id", id)
        .query(
            (row, index) ->
                new OfferRow(
                    row.getObject("id", UUID.class),
                    row.getObject("order_id", UUID.class),
                    row.getObject("courier_id", UUID.class),
                    OfferStatus.valueOf(row.getString("status")),
                    row.getInt("eta_to_pickup_s"),
                    row.getTimestamp("created_at").toInstant(),
                    row.getTimestamp("expires_at").toInstant()))
        .optional()
        .orElseThrow(DispatchCoordinator::missing);
  }

  private void scheduleNow(UUID id, Instant now) {
    jdbc.sql(
            "UPDATE orders SET next_attempt_at=GREATEST(created_at,:now) WHERE id=:id AND status='SEARCHING'")
        .param("id", id)
        .param("now", timestamp(now))
        .update();
  }

  private static void requireCourier(DemoPrincipal user) {
    if (user.role() != DemoRole.COURIER) throw forbidden();
  }

  private static ApiException forbidden() {
    return new ApiException(
        HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", "Este papel não pode realizar esta ação.");
  }

  private static ApiException missing() {
    return new ApiException(
        HttpStatus.NOT_FOUND, "OFFER_OR_ORDER_NOT_FOUND", "Oferta ou pedido não encontrado.");
  }

  private static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }

  private static OffsetDateTime timestamp(Instant now) {
    return now.atOffset(ZoneOffset.UTC);
  }

  private record Candidate(
      UUID id, long epoch, Instant recordedAt, GeoPoint location, long durationMillis) {}

  private record RankedRow(UUID id, int rank, String recorded, long epoch) {}

  private record SelectedCourier(UUID id, boolean simulated) {}

  private record OfferRow(
      UUID id,
      UUID orderId,
      UUID courierId,
      OfferStatus status,
      int eta,
      Instant createdAt,
      Instant expiresAt) {}
}
