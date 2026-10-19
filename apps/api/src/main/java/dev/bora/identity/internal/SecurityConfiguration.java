package dev.bora.identity.internal;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

  @Bean
  UserDetailsService demoUsers() {
    return username -> {
      throw new UsernameNotFoundException("Use a entrada de demonstração para escolher um papel.");
    };
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  CsrfTokenRepository csrfTokenRepository() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  SecurityFilterChain applicationSecurity(
      HttpSecurity http,
      SecurityContextRepository contexts,
      CsrfTokenRepository csrfTokens,
      DemoIdentityService identity,
      JsonMapper json)
      throws Exception {
    http.formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .securityContext(context -> context.securityContextRepository(contexts))
        .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        "/api/v1/auth/me",
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/demo",
                        "/api/v1/auth/logout",
                        "/api/v1/map/snapshot",
                        "/api/v1/tracking/**",
                        "/ws",
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                        "/api/openapi",
                        "/api/openapi/**",
                        "/error")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, failure) ->
                            writeProblem(
                                response,
                                json,
                                HttpStatus.UNAUTHORIZED,
                                "UNAUTHENTICATED",
                                "Entre na demonstração para continuar."))
                    .accessDeniedHandler(
                        (request, response, failure) ->
                            writeProblem(
                                response,
                                json,
                                HttpStatus.FORBIDDEN,
                                failure instanceof CsrfException ? "INVALID_CSRF" : "FORBIDDEN",
                                failure instanceof CsrfException
                                    ? "Atualize a página e tente novamente. A proteção da sessão expirou."
                                    : "Seu papel não permite esta ação.")))
        .addFilterAfter(new DemoSessionFilter(identity), SecurityContextHolderFilter.class);
    return http.build();
  }

  private static void writeProblem(
      HttpServletResponse response, JsonMapper json, HttpStatus status, String code, String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle("Não foi possível concluir a solicitação.");
    problem.setType(URI.create("urn:bora:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
    problem.setProperty("code", code);
    json.writeValue(response.getOutputStream(), problem);
  }
}
