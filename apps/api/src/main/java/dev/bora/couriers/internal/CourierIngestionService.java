package dev.bora.couriers.internal;

import dev.bora.couriers.CourierDirectory;
import dev.bora.couriers.CourierStatus;
import dev.bora.couriers.CourierView;
import dev.bora.couriers.PositionReceipt;
import dev.bora.couriers.PositionSample;
import dev.bora.demo.DemoLimits;
import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.routing.GeoPoint;
import dev.bora.routing.RoutingException;
import dev.bora.routing.RoutingService;
import dev.bora.shared.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class CourierIngestionService {
  private final CourierDirectory directory;
  private final CourierPositionBuffer buffer;
  private final DemoLimits limits;
  private final RoutingService routing;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final TransactionTemplate transaction;

  CourierIngestionService(
      CourierDirectory directory,
      CourierPositionBuffer buffer,
      DemoLimits limits,
      RoutingService routing,
      JdbcClient jdbc,
      Clock clock,
      PlatformTransactionManager manager) {
    this.directory = directory;
    this.buffer = buffer;
    this.limits = limits;
    this.routing = routing;
    this.jdbc = jdbc;
    this.clock = clock;
    transaction = new TransactionTemplate(manager);
  }

  CourierView me(DemoPrincipal user) {
    return directory.find(requireCourier(user));
  }

  CourierView availability(DemoPrincipal user, AvailabilityRequest request) {
    UUID id = requireCourier(user);
    if (request.online() && request.position() == null) {
      throw failure(
          HttpStatus.BAD_REQUEST, "POSITION_REQUIRED", "Informe sua posição para ficar online.");
    }
    if (request.online()) {
      validatePosition(request.position());
    }
    return transaction.execute(
        status -> {
          CourierView courier = directory.lock(id);
          if (courier.status() == CourierStatus.OFFERED || courier.status() == CourierStatus.BUSY) {
            throw failure(
                HttpStatus.CONFLICT,
                "COURIER_HAS_ACTIVE_WORK",
                "Conclua a oferta ou entrega atual antes de alterar a disponibilidade.");
          }
          if (request.online()) {
            if (courier.status() == CourierStatus.AVAILABLE) {
              return courier;
            }
            GeoPoint point = request.position();
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (courier.locationUpdatedAt() != null && !now.isAfter(courier.locationUpdatedAt())) {
              now = courier.locationUpdatedAt().plus(1, ChronoUnit.MICROS);
            }
            jdbc.sql(
                    """
            UPDATE couriers SET status='AVAILABLE',
              location=ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)::geography,
              location_updated_at=:recorded,
              version=version+1,position_epoch=position_epoch+1
            WHERE id=:id
            """)
                .param("id", id)
                .param("longitude", point.longitude())
                .param("latitude", point.latitude())
                .param("recorded", now.atOffset(ZoneOffset.UTC))
                .update();
          } else if (courier.status() != CourierStatus.OFFLINE) {
            jdbc.sql(
                    "UPDATE couriers SET status='OFFLINE',version=version+1,position_epoch=position_epoch+1 WHERE id=:id")
                .param("id", id)
                .update();
          }
          return directory.find(id);
        });
  }

  PositionReceipt positions(DemoPrincipal user, List<PositionSample> samples) {
    UUID id = requireCourier(user);
    limits.checkPositions(id);
    IngestionState state =
        jdbc.sql("SELECT status,location_updated_at,position_epoch FROM couriers WHERE id=:id")
            .param("id", id)
            .query(
                (row, index) ->
                    new IngestionState(
                        CourierStatus.valueOf(row.getString("status")),
                        row.getTimestamp("location_updated_at") == null
                            ? null
                            : row.getTimestamp("location_updated_at").toInstant(),
                        row.getLong("position_epoch")))
            .optional()
            .orElseThrow(
                () ->
                    failure(
                        HttpStatus.NOT_FOUND, "COURIER_NOT_FOUND", "Entregador não encontrado."));
    if (state.status() == CourierStatus.OFFLINE) {
      throw failure(
          HttpStatus.CONFLICT, "COURIER_OFFLINE", "Fique online antes de enviar posições.");
    }
    if (samples == null || samples.isEmpty() || samples.size() > 40) {
      throw failure(
          HttpStatus.BAD_REQUEST, "INVALID_POSITION_BATCH", "Envie de 1 a 40 posições por lote.");
    }
    Instant now = clock.instant();
    Instant previous = null;
    List<PositionSample> normalized = new ArrayList<>(samples.size());
    for (PositionSample sample : samples) {
      if (sample == null || sample.recordedAt() == null || sample.position() == null) {
        throw failure(
            HttpStatus.BAD_REQUEST, "INVALID_POSITION", "Informe posição e horário válidos.");
      }
      Instant recorded = sample.recordedAt().truncatedTo(ChronoUnit.MICROS);
      if (recorded.isBefore(now.minusSeconds(120)) || recorded.isAfter(now.plusSeconds(5))) {
        throw failure(
            HttpStatus.BAD_REQUEST,
            "INVALID_POSITION_TIME",
            "A posição deve ter até dois minutos e não pode estar mais de cinco segundos no futuro.");
      }
      if (previous != null && !recorded.isAfter(previous)) {
        throw failure(
            HttpStatus.BAD_REQUEST,
            "UNORDERED_POSITIONS",
            "Envie as posições em ordem de horário, sem repetir microssegundos.");
      }
      validatePosition(sample.position());
      normalized.add(new PositionSample(recorded, sample.position()));
      previous = recorded;
    }
    return buffer.enqueue(id, normalized, state.recordedAt(), state.epoch());
  }

  private void validatePosition(GeoPoint point) {
    GeoPoint matched;
    try {
      matched = routing.snap(point).matched();
    } catch (RoutingException failure) {
      throw failure(
          failure.reason() == RoutingException.Reason.GRAPH_UNAVAILABLE
              ? HttpStatus.SERVICE_UNAVAILABLE
              : HttpStatus.UNPROCESSABLE_ENTITY,
          failure.reason() == RoutingException.Reason.GRAPH_UNAVAILABLE
              ? "ROUTING_UNAVAILABLE"
              : "POSITION_UNREACHABLE",
          failure.reason() == RoutingException.Reason.GRAPH_UNAVAILABLE
              ? "O roteamento ainda está sendo preparado. Tente novamente em instantes."
              : "Escolha uma posição próxima de uma via acessível a motos.");
    }
    boolean covered =
        jdbc.sql(
                """
        SELECT EXISTS (SELECT 1 FROM service_areas WHERE
          ST_Covers(polygon::geometry,ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)) AND
          ST_Covers(polygon::geometry,ST_SetSRID(ST_MakePoint(:matchedLongitude,:matchedLatitude),4326)))
        """)
            .param("longitude", point.longitude())
            .param("latitude", point.latitude())
            .param("matchedLongitude", matched.longitude())
            .param("matchedLatitude", matched.latitude())
            .query(Boolean.class)
            .single();
    if (!covered) {
      throw failure(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "OUTSIDE_SERVICE_AREA",
          "Escolha uma posição dentro da área atendida.");
    }
  }

  private static UUID requireCourier(DemoPrincipal user) {
    if (user.role() != DemoRole.COURIER || user.courierId() == null) {
      throw failure(
          HttpStatus.FORBIDDEN, "COURIER_ROLE_REQUIRED", "Entre como entregador para continuar.");
    }
    return user.courierId();
  }

  private static ApiException failure(HttpStatus status, String code, String message) {
    return new ApiException(status, code, message);
  }

  private record IngestionState(CourierStatus status, Instant recordedAt, long epoch) {}
}
