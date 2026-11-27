package io.capyiot.gateway;

import io.capyiot.contract.internal.PlatformApi;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** Every authenticated request checks the live session and grants. */
@Component
class CurrentAuthorization {
  private final PlatformIdentityClient platform;

  CurrentAuthorization(PlatformIdentityClient platform) {
    this.platform = platform;
  }

  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (path.equals("/api/v1/auth/logout")) return chain.filter(exchange);
    return ReactiveSecurityContextHolder.getContext()
        .map(SecurityContext::getAuthentication)
        .ofType(JwtAuthenticationToken.class)
        .flatMap(
            auth ->
                platform
                    .context(auth.getToken().getTokenValue(), exchange.getRequest().getId())
                    .map(
                        context -> {
                          var jwt = auth.getToken();
                          Number version = jwt.getClaim("authz_ver");
                          if (!context.userId().equals(jwt.getSubject())
                              || !context.sessionId().equals(jwt.getClaimAsString("sid"))
                              || version == null
                              || context.authorizationVersion() != version.longValue())
                            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "会话授权已变化");
                          exchange.getAttributes().put("capyiot.authorization", context);
                          return true;
                        }))
        .defaultIfEmpty(false)
        .onErrorMap(
            error ->
                new AuthorizationFailure(
                    error instanceof ResponseStatusException response
                        && response.getStatusCode().value() == 401))
        .flatMap(
            authenticated -> {
              if (path.startsWith("/api/v1/observability/")) {
                PlatformApi.Identity context = exchange.getAttribute("capyiot.authorization");
                boolean admin =
                    context != null
                        && context.grants().stream()
                            .anyMatch(
                                grant ->
                                    grant.roleCode().equals("SYS_ADMIN")
                                        && grant.scopeType() == PlatformApi.ScopeType.NONE);
                if (authenticated && !admin)
                  return ApiErrors.write(
                      exchange, HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "系统运行信息需要平台管理员权限");
              }
              return chain.filter(exchange);
            })
        .onErrorResume(
            AuthorizationFailure.class,
            error ->
                ApiErrors.write(
                    exchange,
                    error.unauthorized ? HttpStatus.UNAUTHORIZED : HttpStatus.SERVICE_UNAVAILABLE,
                    error.unauthorized ? "SESSION_INVALID" : "AUTHORIZATION_UNAVAILABLE",
                    "会话失效或授权服务不可用"));
  }

  private static final class AuthorizationFailure extends RuntimeException {
    final boolean unauthorized;

    AuthorizationFailure(boolean unauthorized) {
      this.unauthorized = unauthorized;
    }
  }
}
