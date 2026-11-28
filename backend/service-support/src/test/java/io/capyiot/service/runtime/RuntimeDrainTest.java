package io.capyiot.service.runtime;

import io.capyiot.contract.RuntimeGate;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

class RuntimeDrainTest {
    @Test void servletDrainClosesPublicAndInternalConnectionsButStillServesKeys() throws Exception {
        try (var gate = mockStatic(RuntimeGate.class)) {
            gate.when(RuntimeGate::draining).thenReturn(true);
            var filter = new RuntimeAutoConfiguration.ServletRuntime().drainFilter();
            for (String path : new String[]{"/api/v1/workorders", "/internal/v1/identity/context", "/.well-known/service-jwks.json"}) {
                var request = new MockHttpServletRequest("GET", path);
                var response = new MockHttpServletResponse();
                var forwarded = new AtomicBoolean();
                filter.doFilter(request, response, (req,res) -> forwarded.set(true));
                if (path.startsWith("/.well-known/")) assertThat(forwarded).isTrue();
                else {
                    assertThat(forwarded).isFalse();
                    assertThat(response.getStatus()).isEqualTo(503);
                    assertThat(response.getHeader("Connection")).isEqualTo("close");
                }
            }
        }
    }

    @Test void reactiveDrainClosesNewCommandsAndKeepsLivenessAccessible() {
        try (var gate = mockStatic(RuntimeGate.class)) {
            gate.when(RuntimeGate::draining).thenReturn(true);
            var filter = new RuntimeAutoConfiguration.ReactiveRuntime().drainFilter();
            for (String path : new String[]{"/api/v1/users", "/internal/v1/identity/context", "/actuator/health/liveness"}) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
                var forwarded = new AtomicBoolean();
                filter.filter(exchange, ex -> { forwarded.set(true); return Mono.empty(); }).block();
                if (path.startsWith("/actuator/")) assertThat(forwarded).isTrue();
                else {
                    assertThat(forwarded).isFalse();
                    assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
                    assertThat(exchange.getResponse().getHeaders().getFirst("Connection")).isEqualTo("close");
                }
            }
        }
    }
}
