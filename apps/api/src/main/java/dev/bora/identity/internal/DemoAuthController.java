package dev.bora.identity.internal;

import dev.bora.demo.DemoLimits;
import dev.bora.identity.DemoRole;
import dev.bora.identity.DemoUserView;
import dev.bora.identity.SessionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class DemoAuthController {

  private static final String CONTEXT_ATTRIBUTE = "bora.demo.context";
  private final DemoIdentityService identity;
  private final DemoLimits limits;
  private final SecurityContextRepository contexts;
  private final CsrfTokenRepository csrfTokens;
  private final SessionAuthenticationStrategy sessionAuthentication;
  private final SimulatorGuard simulatorGuard;
  private final SimulatorDirectory simulator;

  DemoAuthController(
      DemoIdentityService identity,
      DemoLimits limits,
      SecurityContextRepository contexts,
      CsrfTokenRepository csrfTokens,
      SimulatorGuard simulatorGuard,
      SimulatorDirectory simulator) {
    this.identity = identity;
    this.limits = limits;
    this.contexts = contexts;
    this.csrfTokens = csrfTokens;
    this.simulatorGuard = simulatorGuard;
    this.simulator = simulator;
    this.sessionAuthentication =
        new CompositeSessionAuthenticationStrategy(
            List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokens)));
  }

  @GetMapping("/csrf")
  @Operation(operationId = "getCsrf", summary = "Obter a proteção CSRF da sessão")
  CsrfView csrf(@Parameter(hidden = true) CsrfToken token) {
    return new CsrfView(token.getHeaderName(), token.getToken());
  }

  @GetMapping("/me")
  @Operation(operationId = "getSession", summary = "Consultar a sessão atual")
  SessionView me(HttpServletRequest request) {
    Object user = request.getAttribute(DemoSessionFilter.USER_VIEW_ATTRIBUTE);
    return user instanceof DemoUserView view
        ? new SessionView(true, view)
        : SessionView.anonymous();
  }

  @PostMapping("/demo")
  @Operation(operationId = "enterDemo", summary = "Entrar na demonstração ou trocar de papel")
  SessionView demo(
      @Valid @RequestBody DemoLogin requestBody,
      HttpServletRequest request,
      HttpServletResponse response) {
    limits.checkDemoLogin(request.getRemoteAddr());
    HttpSession session = request.getSession();
    UUID context = session.getAttribute(CONTEXT_ATTRIBUTE) instanceof UUID id ? id : null;
    DemoIdentityService.Login login = identity.login(requestBody.role(), context);
    return establish(login, request, response);
  }

  @PostMapping("/simulator")
  @Operation(hidden = true)
  SessionView simulator(
      @RequestHeader(value = SimulatorGuard.HEADER, required = false) String key,
      @Valid @RequestBody SimulatorLogin body,
      HttpServletRequest request,
      HttpServletResponse response) {
    simulatorGuard.require(key);
    DemoIdentityService.Login login =
        identity.simulatorLogin(simulator.actor(body.role(), body.index()));
    return establish(login, request, response);
  }

  private SessionView establish(
      DemoIdentityService.Login login, HttpServletRequest request, HttpServletResponse response) {
    UsernamePasswordAuthenticationToken authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            login.principal(),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + login.principal().role().name())));
    sessionAuthentication.onAuthentication(authentication, request, response);
    request.getSession().setAttribute(CONTEXT_ATTRIBUTE, login.principal().contextId());
    SecurityContext security = SecurityContextHolder.createEmptyContext();
    security.setAuthentication(authentication);
    SecurityContextHolder.setContext(security);
    contexts.saveContext(security, request, response);
    return new SessionView(true, login.user());
  }

  @PostMapping("/logout")
  @Operation(
      operationId = "logoutDemo",
      summary = "Sair da demonstração e encerrar o contexto visitante")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(HttpServletRequest request, HttpServletResponse response) {
    HttpSession session = request.getSession(false);
    UUID context =
        session != null && session.getAttribute(CONTEXT_ATTRIBUTE) instanceof UUID id ? id : null;
    identity.invalidate(context);
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    new CsrfLogoutHandler(csrfTokens).logout(request, response, authentication);
    new SecurityContextLogoutHandler().logout(request, response, authentication);
    contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
  }

  @Schema(requiredProperties = "role")
  record DemoLogin(
      @NotNull(message = "Escolha um papel para entrar na demonstração.") DemoRole role) {}

  record SimulatorLogin(@NotNull DemoRole role, @NotNull Integer index) {}

  @Schema(requiredProperties = {"headerName", "token"})
  record CsrfView(String headerName, String token) {}
}
