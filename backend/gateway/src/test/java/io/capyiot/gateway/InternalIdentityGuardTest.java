package io.capyiot.gateway;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

class InternalIdentityGuardTest {
    @Test void internalRoutesNeverReachAProxyRoute() {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/internal/v1/identity/context"));
        new InternalIdentityGuard().filter(exchange,e->{throw new AssertionError("Internal route reached downstream");}).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void publicBearerSurvivesWhileForgedServiceIdentityHeadersAreRemoved() {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/auth/me").header("Authorization","Bearer user")
                .header("X-Core-Service-Key","forged").header("X-Service-Authorization","Bearer forged").header("X-User-Id","admin").header("X-CapyIoT-Deadline","9999999999999"));
        var captured=new AtomicReference<org.springframework.web.server.ServerWebExchange>();
        new InternalIdentityGuard().filter(exchange,e->{captured.set(e);return Mono.empty();}).block();
        var headers=captured.get().getRequest().getHeaders();assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer user");
        for(String name:java.util.List.of("X-Core-Service-Key","X-Service-Authorization","X-User-Id","X-CapyIoT-Deadline"))assertThat(headers.containsKey(name)).isFalse();
    }
}
