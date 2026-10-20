package dev.bora.dispatch;

import dev.bora.couriers.CourierCommand;
import dev.bora.couriers.CourierDirectory;
import dev.bora.couriers.CourierStatus;
import dev.bora.couriers.CourierView;
import dev.bora.demo.DemoLimits;
import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.orders.TrainingOrderReceipt;
import dev.bora.orders.TrainingOrderState;
import dev.bora.orders.TrainingOrders;
import dev.bora.routing.GeoPoint;
import dev.bora.routing.Route;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingService;
import dev.bora.shared.ApiException;
import dev.bora.stores.StoreView;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Cria o ensaio e reserva uma oferta normal ao visitante, sem aceitar ou executar a entrega. */
@Service
public class TrainingOrderCoordinator {
  private final TrainingOrders orders;
  private final CourierDirectory couriers;
  private final RoutingService routing;
  private final DemoLimits limits;
  private final DispatchActivation activation;
  private final OfferAudit audit;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public TrainingOrderCoordinator(
      TrainingOrders orders,
      CourierDirectory couriers,
      RoutingService routing,
      DemoLimits limits,
      DispatchActivation activation,
      OfferAudit audit,
      JdbcClient jdbc,
      Clock clock,
      PlatformTransactionManager manager) {
    this.orders = orders;
    this.couriers = couriers;
    this.routing = routing;
    this.limits = limits;
    this.activation = activation;
    this.audit = audit;
    this.jdbc = jdbc;
    this.clock = clock;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public TrainingOrderReceipt create(DemoPrincipal user, String key) {
    if (user.role() != DemoRole.COURIER || user.courierId() == null || !user.visitor()) {
      throw failure(
          HttpStatus.FORBIDDEN,
          "TRAINING_COURIER_REQUIRED",
          "Entre como entregador visitante para criar um pedido de treino.");
    }
    TrainingOrderReceipt previous = orders.replayTraining(user, key);
    if (previous != null) return previous;
    // O limitador termina antes do lock do contexto e de qualquer conexão transacional.
    limits.checkOrderCreation(user.contextId());
    return transaction.execute(
        status -> {
          TrainingOrderReceipt repeated = orders.lockAndReplayTraining(user, key);
          if (repeated != null) return repeated;
          requireActivation();
          CourierView observed = couriers.find(user.courierId());
          requireAvailable(observed);
          TrainingPlan plan = plan(observed.location());
          requireActivation();
          // Confere a validade do contexto novamente após o cálculo das rotas.
          orders.lockAndReplayTraining(user, key);
          TrainingOrderReceipt created =
              orders.createTraining(user, key, plan.store(), plan.dropoff(), UUID.randomUUID());
          // A ordem criada ainda não pode ser vista pelo despachante. Um único lock de
          // entregador vem depois do pedido, como no fluxo normal de oferta.
          CourierView current = couriers.lock(user.courierId());
          requireAvailable(current);
          if (current.version() != observed.version()
              || !current.locationUpdatedAt().equals(observed.locationUpdatedAt())
              || !current.location().equals(observed.location())) {
            throw failure(
                HttpStatus.CONFLICT,
                "COURIER_POSITION_CHANGED",
                "Sua posição mudou durante a preparação do treino. Tente novamente.");
          }
          requireActivation();
          couriers.transition(user.courierId(), new CourierCommand.ReceiveOffer());
          jdbc.sql(
                  """
          INSERT INTO offers(id,order_id,courier_id,status,eta_to_pickup_s,created_at,expires_at)
          VALUES(:id,:order,:courier,'PENDING',:eta,:now,:expires)
          """)
              .param("id", created.offerId())
              .param("order", created.orderId())
              .param("courier", user.courierId())
              .param("eta", plan.etaSeconds())
              .param("now", timestamp(created.serverTime()))
              .param("expires", timestamp(created.expiresAt()))
              .update();
          jdbc.sql("UPDATE orders SET next_attempt_at=:expires WHERE id=:id")
              .param("id", created.orderId())
              .param("expires", timestamp(created.expiresAt()))
              .update();
          audit.write(
              created.orderId(),
              "OFFER_CREATED",
              created.offerId(),
              user.courierId(),
              null,
              OfferStatus.PENDING,
              "user:" + user.userId(),
              created.serverTime());
          return created;
        });
  }

  public TrainingOrderState cancel(DemoPrincipal user, UUID orderId, long expectedVersion) {
    return orders.cancelTraining(user, orderId, expectedVersion);
  }

  private void requireAvailable(CourierView courier) {
    if (courier.status() != CourierStatus.AVAILABLE) {
      throw failure(
          HttpStatus.CONFLICT,
          "COURIER_UNAVAILABLE_FOR_TRAINING",
          "Fique online e conclua a oferta ou entrega atual antes de iniciar um treino.");
    }
    Instant now = clock.instant();
    if (courier.location() == null
        || courier.locationUpdatedAt() == null
        || courier.locationUpdatedAt().isBefore(now.minusSeconds(30))
        || courier.locationUpdatedAt().isAfter(now.plusSeconds(5))) {
      throw failure(
          HttpStatus.CONFLICT,
          "COURIER_POSITION_STALE",
          "Envie uma posição recente antes de iniciar um pedido de treino.");
    }
  }

  private TrainingPlan plan(GeoPoint origin) {
    List<StoreView> stores =
        jdbc.sql(
                """
        SELECT id,name,service_area_id,ST_Y(location::geometry) latitude,
          ST_X(location::geometry) longitude FROM stores WHERE simulated
        ORDER BY location <-> ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,id LIMIT 8
        """)
            .param("lon", origin.longitude())
            .param("lat", origin.latitude())
            .query(
                (row, index) ->
                    new StoreView(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        new GeoPoint(row.getDouble("latitude"), row.getDouble("longitude")),
                        row.getObject("service_area_id", UUID.class)))
            .list();
    for (StoreView store : stores) {
      try {
        Route pickup = routing.route(origin, store.location());
        // A direção sobre as vias precisa terminar dentro do raio real de chegada.
        if (pickup.destination().distanceMeters() > 60) continue;
        List<GeoPoint> destinations =
            jdbc.sql(
                    """
            SELECT ST_Y(location::geometry) latitude,ST_X(location::geometry) longitude
            FROM stores WHERE simulated AND service_area_id=:area AND id<>:store
              AND ST_DWithin(location,ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,2500)
              AND NOT ST_DWithin(location,ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,300)
            ORDER BY location <-> ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography,id LIMIT 8
            """)
                .param("area", store.serviceAreaId())
                .param("store", store.id())
                .param("lon", store.location().longitude())
                .param("lat", store.location().latitude())
                .query(
                    (row, index) ->
                        new GeoPoint(row.getDouble("latitude"), row.getDouble("longitude")))
                .list();
        for (GeoPoint destination : destinations) {
          try {
            routing.route(store.location(), destination);
            long millis = pickup.simulatedDuration().toMillis();
            int eta =
                Math.toIntExact(
                    Math.min(Integer.MAX_VALUE, millis / 1000 + (millis % 1000 == 0 ? 0 : 1)));
            return new TrainingPlan(store, destination, eta);
          } catch (RoutingException missing) {
            if (missing.reason() == RoutingException.Reason.GRAPH_UNAVAILABLE) throw missing;
          }
        }
      } catch (RoutingException missing) {
        if (missing.reason() == RoutingException.Reason.GRAPH_UNAVAILABLE) {
          throw failure(
              HttpStatus.SERVICE_UNAVAILABLE,
              "ROUTING_UNAVAILABLE",
              "O roteamento ainda está sendo preparado. Tente novamente em instantes.");
        }
      }
    }
    throw failure(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "TRAINING_ROUTE_UNAVAILABLE",
        "Não encontramos um treino próximo dessa posição. Tente uma posição perto da Paulista.");
  }

  private void requireActivation() {
    if (!activation.allowsCreation()) {
      throw failure(
          HttpStatus.SERVICE_UNAVAILABLE,
          "DISPATCH_PAUSED",
          "O despacho está sendo atualizado. Tente criar o treino em instantes.");
    }
  }

  private static OffsetDateTime timestamp(Instant at) {
    return at.atOffset(ZoneOffset.UTC);
  }

  private static ApiException failure(HttpStatus status, String code, String detail) {
    return new ApiException(status, code, detail);
  }

  private record TrainingPlan(StoreView store, GeoPoint dropoff, int etaSeconds) {}
}
