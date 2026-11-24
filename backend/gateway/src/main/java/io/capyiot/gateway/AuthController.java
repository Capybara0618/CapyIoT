package io.capyiot.gateway;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.capyiot.contract.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
class AuthController {
    private final WebClient core;
    private final UserKeyRing keys;
    AuthController(WebClient.Builder clients,UserKeyRing keys,@Value("${capyiot.services.core-url}") String url,
                   @Value("${capyiot.auth.core-service-key}") String serviceKey) {
        this.core=clients.baseUrl(url).defaultHeader("X-Core-Service-Key",serviceKey).build(); this.keys=keys;
    }
    @GetMapping("/.well-known/jwks.json")
    Map<String,Object> jwks() { return keys.publicKeys(); }
    @PostMapping("/api/v1/auth/login")
    Mono<ApiResponse<LoginResult>> login(@Valid @RequestBody LoginRequest request,ServerWebExchange exchange) {
        return core.post().uri("/internal/foundation/login").bodyValue(request).retrieve().bodyToMono(SessionIdentity.class)
                .timeout(java.time.Duration.ofSeconds(8))
                .map(identity->{
                    var claims=JwtClaimsSet.builder().issuer(SecurityConfiguration.ISSUER).subject(identity.userId())
                            .audience(List.of("capyiot-api")).issuedAt(Instant.now()).expiresAt(identity.expiresAt()).id(identity.jti())
                            .claim("sid",identity.sessionId()).claim("authz_ver",identity.authorizationVersion()).build();
                    String token=keys.encoder().encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.activeId()).build(),claims)).getTokenValue();
                    var user=new UserInfo(identity.userId(),identity.loginName(),identity.displayName(),identity.authorizationVersion(),identity.roleCodes());
                    return ApiResponse.ok(new LoginResult(token,"Bearer",identity.expiresAt(),user),exchange.getRequest().getId());
                }).onErrorMap(WebClientResponseException.class,error->new ResponseStatusException(
                        error.getStatusCode().value()==401?HttpStatus.UNAUTHORIZED:HttpStatus.SERVICE_UNAVAILABLE,
                        error.getStatusCode().value()==401?"账号或密码错误":"认证服务暂不可用"))
                .onErrorMap(java.util.concurrent.TimeoutException.class,error->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"认证服务暂不可用"))
                .onErrorMap(org.springframework.web.reactive.function.client.WebClientRequestException.class,error->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"认证服务暂不可用"));
    }
    record LoginRequest(@JsonAlias("username") @NotBlank @Size(max=64) String loginName,@NotBlank @Size(max=256) String password) {}
    record SessionIdentity(String userId,String loginName,String displayName,long authorizationVersion,String sessionId,String jti,Instant expiresAt,List<String> roleCodes) {}
    record UserInfo(String userId,String loginName,String displayName,long authorizationVersion,List<String> roleCodes) {}
    record LoginResult(String accessToken,String tokenType,Instant expiresAt,UserInfo user) {}
}
