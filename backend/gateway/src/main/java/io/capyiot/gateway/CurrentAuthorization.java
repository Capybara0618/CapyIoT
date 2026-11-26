package io.capyiot.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** Session and grants are loaded for every request. JWT role hints never authorize a route. */
@Component
class CurrentAuthorization {
    private final WebClient platform;
    CurrentAuthorization(WebClient.Builder builder,@Value("${capyiot.services.platform-url}") String url,
                         @Value("${capyiot.auth.core-service-key}") String key,PlatformWebClientFactory platformClients) {
        platform=platformClients.create(builder,url,key);
    }
    public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        String path=exchange.getRequest().getPath().value();
        if(path.equals("/api/v1/auth/logout")) return chain.filter(exchange);
        return org.springframework.security.platform.context.ReactiveSecurityContextHolder.getContext()
                .map(org.springframework.security.platform.context.SecurityContext::getAuthentication).ofType(JwtAuthenticationToken.class)
                .flatMap(auth->platform.get().uri("/internal/foundation/context")
                        .headers(h->h.setBearerAuth(auth.getToken().getTokenValue())).retrieve().bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(3)).map(context->{exchange.getAttributes().put("capyiot.authorization",context); return true;}))
                .defaultIfEmpty(false)
                .onErrorMap(error->new AuthorizationFailure(error instanceof WebClientResponseException response&&response.getStatusCode().value()==401))
                .flatMap(authenticated->{
                    if(path.startsWith("/api/v1/observability/")){
                        JsonNode context=exchange.getAttribute("capyiot.authorization");boolean platform=false;
                        if(context!=null)for(JsonNode grant:context.path("grants"))if(grant.path("roleCode").asText().equals("SYS_ADMIN")&&grant.path("scopeType").asText().equals("NONE"))platform=true;
                        if(authenticated&&!platform)return ApiErrors.write(exchange,HttpStatus.FORBIDDEN,"PERMISSION_DENIED","系统运行信息需要平台管理员权限");
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(AuthorizationFailure.class,error->ApiErrors.write(exchange,error.unauthorized?HttpStatus.UNAUTHORIZED:HttpStatus.SERVICE_UNAVAILABLE,
                        error.unauthorized?"SESSION_INVALID":"AUTHORIZATION_UNAVAILABLE","会话失效或授权服务不可用"));
    }
    private static final class AuthorizationFailure extends RuntimeException {
        final boolean unauthorized;
        AuthorizationFailure(boolean unauthorized) { this.unauthorized=unauthorized; }
    }
}
