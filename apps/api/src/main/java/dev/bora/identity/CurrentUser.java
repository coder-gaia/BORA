package dev.bora.identity;

import dev.bora.shared.ApiException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

  public Optional<DemoPrincipal> optional() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null
            && authentication.isAuthenticated()
            && authentication.getPrincipal() instanceof DemoPrincipal principal
        ? Optional.of(principal)
        : Optional.empty();
  }

  public DemoPrincipal require() {
    return optional()
        .orElseThrow(
            () ->
                new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED",
                    "Entre na demonstração para continuar."));
  }
}
