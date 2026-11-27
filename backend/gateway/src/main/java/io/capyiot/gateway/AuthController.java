package io.capyiot.gateway;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.capyiot.contract.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
class AuthController {
  private final PlatformIdentityClient platform;
  private final UserKeyRing keys;

  AuthController(PlatformIdentityClient platform, UserKeyRing keys) {
    this.platform = platform;
    this.keys = keys;
  }

  @GetMapping("/.well-known/service-jwks.json")
  Map<String, Object> serviceJwks() {
    return platform.jwks();
  }

  @GetMapping("/.well-known/jwks.json")
  Map<String, Object> jwks() {
    return keys.publicKeys();
  }

  @PostMapping("/api/v1/auth/login")
  Mono<ApiResponse<LoginResult>> login(
      @Valid @RequestBody LoginRequest request, ServerWebExchange exchange) {
    return platform
        .login(
            new io.capyiot.contract.internal.PlatformApi.LoginRequest(
                request.loginName(), request.password()),
            exchange.getRequest().getId())
        .map(
            identity -> {
              var claims =
                  JwtClaimsSet.builder()
                      .issuer(SecurityConfiguration.ISSUER)
                      .subject(identity.userId())
                      .audience(List.of("capyiot-api"))
                      .issuedAt(Instant.now())
                      .expiresAt(identity.expiresAt())
                      .id(identity.jti())
                      .claim("sid", identity.sessionId())
                      .claim("authz_ver", identity.authorizationVersion())
                      .build();
              String token =
                  keys.encoder()
                      .encode(
                          JwtEncoderParameters.from(
                              JwsHeader.with(SignatureAlgorithm.RS256)
                                  .keyId(keys.activeId())
                                  .build(),
                              claims))
                      .getTokenValue();
              var user =
                  new UserInfo(
                      identity.userId(),
                      identity.loginName(),
                      identity.displayName(),
                      identity.authorizationVersion(),
                      identity.roleCodes());
              return ApiResponse.ok(
                  new LoginResult(token, "Bearer", identity.expiresAt(), user),
                  exchange.getRequest().getId());
            });
  }

  record LoginRequest(
      @JsonAlias("username") @NotBlank @Size(max = 64) String loginName,
      @NotBlank @Size(max = 256) String password) {
    @Override
    public String toString() {
      return "LoginRequest[REDACTED]";
    }
  }

  record UserInfo(
      String userId,
      String loginName,
      String displayName,
      long authorizationVersion,
      List<String> roleCodes) {}

  record LoginResult(String accessToken, String tokenType, Instant expiresAt, UserInfo user) {}
}
