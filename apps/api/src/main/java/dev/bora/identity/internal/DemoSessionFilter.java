package dev.bora.identity.internal;

import dev.bora.identity.DemoPrincipal;
import dev.bora.identity.DemoUserView;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class DemoSessionFilter extends OncePerRequestFilter {

  static final String USER_VIEW_ATTRIBUTE = DemoSessionFilter.class.getName() + ".user";
  private final DemoIdentityService identity;

  DemoSessionFilter(DemoIdentityService identity) {
    this.identity = identity;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof DemoPrincipal principal) {
      Optional<DemoUserView> user = identity.touch(principal);
      if (user.isPresent()) {
        request.setAttribute(USER_VIEW_ATTRIBUTE, user.get());
      } else {
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) {
          request.getSession(false).invalidate();
        }
      }
    }
    chain.doFilter(request, response);
  }
}
