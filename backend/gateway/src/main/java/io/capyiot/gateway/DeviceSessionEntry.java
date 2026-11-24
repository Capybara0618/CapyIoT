package io.capyiot.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** Only the trusted TLS edge may forward a public device-session request. */
@Component
@Order(-40)
class DeviceSessionEntry implements WebFilter {
    private record Window(long minute,int count){}
    private final byte[] proxyKey;
    private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
    DeviceSessionEntry(@Value("${capyiot.auth.core-service-key}") String key){proxyKey=key.getBytes(StandardCharsets.UTF_8);}
    public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain){
        if(!exchange.getRequest().getPath().value().equals("/api/v1/device-sessions"))return chain.filter(exchange);
        var headers=exchange.getRequest().getHeaders();String provided=headers.getFirst("X-Session-Proxy-Key");
        if(!"https".equals(headers.getFirst("X-Forwarded-Proto"))||provided==null||!MessageDigest.isEqual(proxyKey,provided.getBytes(StandardCharsets.UTF_8)))
            return ApiErrors.write(exchange,HttpStatus.UPGRADE_REQUIRED,"DEVICE_HTTPS_REQUIRED","设备会话必须通过受信 HTTPS 入口获取");
        long minute=Instant.now().getEpochSecond()/60;
        if(windows.size()>10_000)windows.entrySet().removeIf(e->e.getValue().minute()<minute);
        String address=headers.getFirst("X-Real-IP");if(address==null)address="unknown";
        if(windows.size()>10_000&&!windows.containsKey(address))return ApiErrors.write(exchange,HttpStatus.TOO_MANY_REQUESTS,"DEVICE_AUTH_RATE_LIMITED","设备会话请求过于频繁，请稍后再试");
        var window=windows.compute(address,(ip,previous)->new Window(minute,previous!=null&&previous.minute()==minute?previous.count()+1:1));
        if(window.count()>120)return ApiErrors.write(exchange,HttpStatus.TOO_MANY_REQUESTS,"DEVICE_AUTH_RATE_LIMITED","设备会话请求过于频繁，请稍后再试");
        return chain.filter(exchange);
    }
}
