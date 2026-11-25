package io.capyiot.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class DeviceSessionEntryTest {
    private static final String KEY="test-only-proxy-service-key-over-32-characters";
    @Test void rejectsPlaintextAndUntrustedForwardedHeaders(){
        var filter=new DeviceSessionEntry(KEY);
        for(String provided:new String[]{"", "forged"}){
            var request=MockServerHttpRequest.post("http://gateway/api/v1/device-sessions")
                .header("X-Forwarded-Proto","https").header("X-Session-Proxy-Key",provided).build();
            var exchange=MockServerWebExchange.from(request);var forwarded=new AtomicBoolean();
            filter.filter(exchange,e->{forwarded.set(true);return Mono.empty();}).block();
            assertThat(forwarded).isFalse();assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UPGRADE_REQUIRED);
        }
    }
    @Test void trustedEdgeStillRequiresHttpsAndLimitsRequests(){
        var filter=new DeviceSessionEntry(KEY);
        var plain=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/device-sessions").header("X-Session-Proxy-Key",KEY).header("X-Forwarded-Proto","http"));
        filter.filter(plain,e->Mono.error(new AssertionError("Must not forward plaintext"))).block();
        assertThat(plain.getResponse().getStatusCode()).isEqualTo(HttpStatus.UPGRADE_REQUIRED);
        for(int i=0;i<121;i++){
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/device-sessions").header("X-Session-Proxy-Key",KEY).header("X-Forwarded-Proto","https").header("X-Real-IP","127.0.0.1"));
            var forwarded=new AtomicBoolean();filter.filter(exchange,e->{forwarded.set(true);return Mono.empty();}).block();
            if(i<120)assertThat(forwarded).isTrue();else assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}
