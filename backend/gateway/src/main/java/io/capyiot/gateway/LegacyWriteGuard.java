package io.capyiot.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** Cutover prevents old endpoints from bypassing the new relationship/state invariants. */
@Component
@Order(-50)
class LegacyWriteGuard implements WebFilter {
    private final boolean readOnly;
    LegacyWriteGuard(@Value("${capyiot.legacy-read-only:true}") boolean readOnly){this.readOnly=readOnly;}
    public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain){
        String path=exchange.getRequest().getPath().value();HttpMethod method=exchange.getRequest().getMethod();
        boolean legacy=path.startsWith("/api/v1/locations/")||path.startsWith("/api/v1/topology/links")||path.startsWith("/api/v1/alarms/")
            ||path.startsWith("/api/v1/alarm-rules")||path.startsWith("/api/v1/scenario-runs");
        if(readOnly&&legacy&&method!=HttpMethod.GET&&method!=HttpMethod.HEAD&&method!=HttpMethod.OPTIONS)
            return ApiErrors.write(exchange,HttpStatus.CONFLICT,"LEGACY_READ_ONLY","历史入口只读，请使用当前资产、故障和工单功能");
        return chain.filter(exchange);
    }
}
