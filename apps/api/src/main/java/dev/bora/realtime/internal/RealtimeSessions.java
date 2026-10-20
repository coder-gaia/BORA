package dev.bora.realtime.internal;

import dev.bora.dispatch.CourierOfferInbox;
import dev.bora.dispatch.DispatchCoordinator;
import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoRole;
import dev.bora.orders.OrderSnapshots;
import dev.bora.orders.OrderView;
import dev.bora.realtime.MapFrame;
import dev.bora.realtime.MapViewport;
import dev.bora.realtime.TrackingSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RealtimeSessions {
  static final String HTTP_SESSION = "bora.httpSession";
  static final String PRINCIPAL = "bora.principal";
  private final Map<String, Client> clients = new ConcurrentHashMap<>();
  private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
  private final SessionRepository<? extends Session> sessions;
  private final RealtimeReadModel model;
  private final OrderSnapshots orders;
  private final DispatchCoordinator dispatch;
  private final PostgresChangeBridge bridge;
  private final MessageChannel outbound;
  private final JsonMapper json;

  RealtimeSessions(
      SessionRepository<? extends Session> sessions,
      RealtimeReadModel model,
      OrderSnapshots orders,
      DispatchCoordinator dispatch,
      PostgresChangeBridge bridge,
      @Qualifier("clientOutboundChannel") MessageChannel outbound,
      JsonMapper json) {
    this.sessions = sessions;
    this.model = model;
    this.orders = orders;
    this.dispatch = dispatch;
    this.bridge = bridge;
    this.outbound = outbound;
    this.json = json;
  }

  synchronized boolean opened(WebSocketSession socket) {
    Object httpId = socket.getAttributes().get(HTTP_SESSION);
    if (sockets.size() >= 500
        || sockets.values().stream()
                .filter(
                    existing -> Objects.equals(existing.getAttributes().get(HTTP_SESSION), httpId))
                .count()
            >= 3) {
      return false;
    }
    sockets.put(socket.getId(), socket);
    return true;
  }

  void closed(String id) {
    clients.remove(id);
    sockets.remove(id);
  }

  void disconnect(String id) {
    clients.remove(id);
    closeTransport(id, CloseStatus.NORMAL);
  }

  void connect(String id, Map<String, Object> attributes) {
    if (clients.size() >= 500) {
      throw denied();
    }
    Object sessionId = attributes.get(HTTP_SESSION);
    if (!(sessionId instanceof String httpId)) {
      throw denied();
    }
    DemoPrincipal principal = attributes.get(PRINCIPAL) instanceof DemoPrincipal p ? p : null;
    Client client = new Client(id, httpId, principal);
    if (!valid(client)) {
      throw denied();
    }
    clients.put(id, client);
  }

  boolean valid(String id) {
    Client client = clients.get(id);
    return client != null && valid(client);
  }

  private boolean valid(Client client) {
    Session session = sessions.findById(client.httpId);
    if (session == null || session.isExpired()) {
      return false;
    }
    Object stored = session.getAttribute("SPRING_SECURITY_CONTEXT");
    DemoPrincipal current = null;
    if (stored instanceof SecurityContext context) {
      Authentication authentication = context.getAuthentication();
      if (authentication != null
          && authentication.isAuthenticated()
          && authentication.getPrincipal() instanceof DemoPrincipal p) {
        current = p;
      }
    }
    return Objects.equals(client.principal, current) && (current == null || model.active(current));
  }

  void subscribe(String sessionId, String subscriptionId, String destination) {
    Client client = require(sessionId);
    if (subscriptionId == null
        || subscriptionId.length() > 160
        || destination == null
        || client.subscriptions.size() >= 32) {
      throw denied();
    }
    Subscription subscription = new Subscription(subscriptionId, destination);
    // A leitura inicial também aplica a autorização do recurso antes de registrar a assinatura.
    snapshot(client, subscription);
    client.subscriptions.put(subscriptionId, subscription);
  }

  void unsubscribe(String sessionId, String subscriptionId) {
    Client client = clients.get(sessionId);
    if (client != null && subscriptionId != null) {
      client.subscriptions.remove(subscriptionId);
    }
  }

  void viewport(String id, byte[] body) {
    Client client = require(id);
    long now = System.nanoTime();
    synchronized (client) {
      if (now - client.lastViewport < 200_000_000L) {
        throw denied();
      }
      client.lastViewport = now;
    }
    if (body.length > 2048) {
      throw denied();
    }
    MapViewport viewport = json.readValue(body, MapViewport.class);
    if (viewport.scope() == MapViewport.Scope.OPERATIONS
        && (client.principal == null || client.principal.role() != DemoRole.OPERATIONS)) {
      throw denied();
    }
    client.viewport = viewport;
    client.subscriptions.values().stream()
        .filter(s -> s.destination.equals("/user/queue/map"))
        .forEach(s -> s.fingerprint = null);
  }

  private Client require(String id) {
    Client client = clients.get(id);
    if (client == null || !valid(client)) {
      throw denied();
    }
    return client;
  }

  private Object snapshot(Client client, Subscription subscription) {
    String destination = subscription.destination;
    if (destination.equals("/user/queue/map")) {
      return model.map(client.viewport, client.principal);
    }
    if (destination.startsWith("/topic/tracking/")) {
      return model.tracking(destination.substring("/topic/tracking/".length()));
    }
    if (client.principal == null) {
      throw denied();
    }
    if (destination.equals("/user/queue/events")) {
      String revision = model.revision(client.principal);
      return Map.of(
          "type",
          client.principal.role() == DemoRole.COURIER ? "COURIER_CHANGED" : "ORDERS_CHANGED",
          "eventId",
          revision);
    }
    if (destination.equals("/user/queue/offers") && client.principal.role() == DemoRole.COURIER) {
      return dispatch.inbox(client.principal);
    }
    if (destination.startsWith("/topic/orders/")) {
      UUID id;
      try {
        id = UUID.fromString(destination.substring("/topic/orders/".length()));
      } catch (IllegalArgumentException invalid) {
        throw denied();
      }
      return orders.snapshot(client.principal, id);
    }
    throw denied();
  }

  @Scheduled(fixedDelayString = "${bora.realtime.tick-ms:500}")
  void publish() {
    long now = System.nanoTime();
    long change = bridge.revision();
    for (Client client : clients.values()) {
      try {
        if (!valid(client)) {
          revoke(client.id);
          continue;
        }
        boolean reconcile = now - client.lastReconciled >= 5_000_000_000L;
        for (Subscription subscription : client.subscriptions.values()) {
          boolean map = subscription.destination.equals("/user/queue/map");
          if (map && now - subscription.lastSent < 1_000_000_000L) {
            continue;
          }
          if (!map
              && !reconcile
              && client.bridgeRevision == change
              && subscription.fingerprint != null) {
            continue;
          }
          try {
            Object snapshot = snapshot(client, subscription);
            String fingerprint = fingerprint(snapshot);
            if (map || !Objects.equals(fingerprint, subscription.fingerprint)) {
              // Revalidação imediatamente antes do envio impede sessão revogada de continuar
              // recebendo.
              if (!valid(client)) {
                revoke(client.id);
                break;
              }
              send(client, subscription, snapshot);
              subscription.fingerprint = fingerprint;
              subscription.lastSent = now;
            }
          } catch (RuntimeException forbidden) {
            client.subscriptions.remove(subscription.id);
          }
        }
        client.bridgeRevision = change;
        if (reconcile) {
          client.lastReconciled = now;
        }
      } catch (RuntimeException unavailable) {
        // Falha fechada: não usar um snapshot privado em cache quando o banco não puder validar a
        // sessão.
        revoke(client.id);
      }
    }
  }

  private String fingerprint(Object snapshot) {
    if (snapshot instanceof CourierOfferInbox inbox) {
      return json.writeValueAsString(inbox.offer());
    }
    if (snapshot instanceof TrackingSnapshot tracking) {
      return tracking.status()
          + ":"
          + tracking.version()
          + ":"
          + tracking.courierPosition()
          + ":"
          + tracking.courierPositionUpdatedAt()
          + ":"
          + (tracking.route() == null ? null : tracking.route().calculatedAt());
    }
    if (snapshot instanceof MapFrame frame) {
      return json.writeValueAsString(frame.points());
    }
    if (snapshot instanceof OrderView order) {
      return json.writeValueAsString(order);
    }
    return json.writeValueAsString(snapshot);
  }

  private void send(Client client, Subscription subscription, Object body) {
    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    headers.setSessionId(client.id);
    headers.setSubscriptionId(subscription.id);
    headers.setDestination(subscription.destination);
    headers.setContentType(MediaType.APPLICATION_JSON);
    outbound.send(
        MessageBuilder.createMessage(
            json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8),
            headers.getMessageHeaders()));
  }

  boolean authorizeOutbound(String id, String subscriptionId, String destination) {
    Client client = clients.get(id);
    if (client == null || !valid(client)) {
      return false;
    }
    Subscription subscription = client.subscriptions.get(subscriptionId);
    if (subscription == null || !subscription.destination.equals(destination)) {
      return false;
    }
    // Também revalida a propriedade do pedido após redistribuição e cancelamento.
    snapshot(client, subscription);
    return true;
  }

  private void revoke(String id) {
    clients.remove(id);
    closeTransport(id, CloseStatus.POLICY_VIOLATION);
  }

  private void closeTransport(String id, CloseStatus status) {
    // O limite conta transportes físicos até o callback de fechamento, mesmo após DISCONNECT.
    WebSocketSession socket = sockets.get(id);
    if (socket != null) {
      try {
        socket.close(status);
      } catch (java.io.IOException ignored) {
        /* fechado */
      }
    }
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("A sessão não permite esta assinatura.");
  }

  private static final class Client {
    final String id;
    final String httpId;
    final DemoPrincipal principal;
    final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    volatile MapViewport viewport =
        new MapViewport(-46.85, -23.75, -46.35, -23.35, MapViewport.Scope.PUBLIC);
    long bridgeRevision = -1;
    long lastReconciled;
    long lastViewport;

    Client(String id, String httpId, DemoPrincipal principal) {
      this.id = id;
      this.httpId = httpId;
      this.principal = principal;
    }
  }

  private static final class Subscription {
    final String id;
    final String destination;
    volatile String fingerprint;
    long lastSent;

    Subscription(String id, String destination) {
      this.id = id;
      this.destination = destination;
    }
  }
}
