package io.capyiot.gateway;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(-200)
class InternalIdentityGuard implements WebFilter {
    public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        if(exchange.getRequest().getPath().value().startsWith("/internal/"))
            return ApiErrors.write(exchange,HttpStatus.FORBIDDEN,"INTERNAL_ROUTE_BLOCKED","内部接口不对外开放");
        var request=exchange.getRequest().mutate().headers(headers->{
            for(String name:java.util.List.of("X-Service-Authorization","X-Core-Service-Key","X-User-Id","X-Actor-UserId","X-CapyIoT-Deadline"))headers.remove(name);
        }).build();return chain.filter(exchange.mutate().request(request).build());
    }
}
