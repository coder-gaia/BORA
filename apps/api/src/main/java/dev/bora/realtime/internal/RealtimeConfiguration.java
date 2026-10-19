package dev.bora.realtime.internal;

import dev.bora.identity.DemoPrincipal;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.messaging.web.csrf.XorCsrfChannelInterceptor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RealtimeConfiguration implements WebSocketMessageBrokerConfigurer {
  private final ObjectProvider<RealtimeSessions> sessions;
  private final CsrfTokenRepository csrf;
  private final ThreadPoolTaskScheduler heartbeat = new ThreadPoolTaskScheduler();

  RealtimeConfiguration(ObjectProvider<RealtimeSessions> sessions, CsrfTokenRepository csrf) {
    this.sessions = sessions;
    this.csrf = csrf;
    heartbeat.setPoolSize(1);
    heartbeat.setThreadNamePrefix("bora-ws-heartbeat-");
    heartbeat.setDaemon(true);
    heartbeat.initialize();
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    // Sem SockJS e sem origens curingas: navegadores permanecem na mesma origem.
    registry
        .addEndpoint("/ws")
        .addInterceptors(
            new HandshakeInterceptor() {
              @Override
              public boolean beforeHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler handler,
                  Map<String, Object> attributes) {
                if (!(request instanceof ServletServerHttpRequest servlet)) {
                  return false;
                }
                var http = servlet.getServletRequest();
                var session = http.getSession(false);
                CsrfToken token = csrf.loadToken(http);
                if (session == null || token == null) {
                  return false;
                }
                attributes.put(RealtimeSessions.HTTP_SESSION, session.getId());
                attributes.put(CsrfToken.class.getName(), token);
                if (http.getUserPrincipal() instanceof Authentication authentication
                    && authentication.getPrincipal() instanceof DemoPrincipal principal) {
                  attributes.put(RealtimeSessions.PRINCIPAL, principal);
                }
                return true;
              }

              @Override
              public void afterHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler handler,
                  Exception failure) {
                /* sem estado externo */
              }
            });
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.setApplicationDestinationPrefixes("/app");
    registry
        .enableSimpleBroker("/topic", "/queue")
        .setTaskScheduler(heartbeat)
        .setHeartbeatValue(new long[] {10000, 10000});
    registry.setPreservePublishOrder(true);
  }

  @PreDestroy
  void closeHeartbeat() {
    heartbeat.shutdown();
  }

  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
    registration
        .setMessageSizeLimit(8192)
        .setSendBufferSizeLimit(128 * 1024)
        .setSendTimeLimit(5000)
        .setTimeToFirstMessage(15000)
        .addDecoratorFactory(
            handler ->
                new WebSocketHandlerDecorator(handler) {
                  @Override
                  public void afterConnectionEstablished(WebSocketSession session)
                      throws Exception {
                    if (!sessions.getObject().opened(session)) {
                      session.close(CloseStatus.POLICY_VIOLATION);
                      return;
                    }
                    super.afterConnectionEstablished(session);
                  }

                  @Override
                  public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
                      throws Exception {
                    sessions.getObject().closed(session.getId());
                    super.afterConnectionClosed(session, status);
                  }
                });
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(
        new XorCsrfChannelInterceptor(),
        new ChannelInterceptor() {
          @Override
          public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor headers =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (headers == null) {
              throw denied();
            }
            String id = headers.getSessionId();
            StompCommand command = headers.getCommand();
            if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
              sessions.getObject().connect(id, headers.getSessionAttributes());
            } else if (command == StompCommand.DISCONNECT) {
              sessions.getObject().disconnect(id);
            } else {
              if (!sessions.getObject().valid(id)) {
                throw denied();
              }
              if (command == StompCommand.SUBSCRIBE) {
                sessions
                    .getObject()
                    .subscribe(id, headers.getSubscriptionId(), headers.getDestination());
              } else if (command == StompCommand.UNSUBSCRIBE) {
                sessions.getObject().unsubscribe(id, headers.getSubscriptionId());
              } else if (command == StompCommand.SEND) {
                if (!"/app/viewport".equals(headers.getDestination())
                    || !(message.getPayload() instanceof byte[] body)) {
                  throw denied();
                }
                sessions.getObject().viewport(id, body);
                return null;
              } else if (command != null) {
                throw denied();
              }
            }
            return message;
          }
        });
  }

  @Override
  public void configureClientOutboundChannel(ChannelRegistration registration) {
    registration.interceptors(
        new ExecutorChannelInterceptor() {
          @Override
          public Message<?> beforeHandle(
              Message<?> message, MessageChannel channel, MessageHandler handler) {
            return authorize(message);
          }

          @Override
          public Message<?> preSend(Message<?> message, MessageChannel channel) {
            return authorize(message);
          }

          private Message<?> authorize(Message<?> message) {
            SimpMessageHeaderAccessor headers =
                MessageHeaderAccessor.getAccessor(message, SimpMessageHeaderAccessor.class);
            if (headers != null && headers.getMessageType() == SimpMessageType.MESSAGE) {
              try {
                if (!sessions
                    .getObject()
                    .authorizeOutbound(
                        headers.getSessionId(),
                        headers.getSubscriptionId(),
                        headers.getDestination())) {
                  return null;
                }
              } catch (RuntimeException unavailable) {
                return null;
              }
            }
            return message;
          }
        });
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("A sessão não permite esta mensagem.");
  }
}
