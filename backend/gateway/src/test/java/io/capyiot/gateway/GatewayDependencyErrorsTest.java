package io.capyiot.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GatewayDependencyErrorsTest {
  @Test
  void unavailableProxyIsExplicitAndACommandDoesNotClaimItWasAborted() {
    var handler = new GatewayDependencyErrors();
    for (var request :
        java.util.List.of(
            MockServerHttpRequest.get("/api/v1/workorders"),
            MockServerHttpRequest.post("/api/v1/workorders"))) {
      var exchange = MockServerWebExchange.from(request);
      exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, new Object());
      handler
          .handle(exchange, new IllegalStateException(new ConnectException("private-host:8084")))
          .block();
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      assertThat(exchange.getResponse().getBodyAsString().block())
          .contains("DEPENDENCY_UNAVAILABLE")
          .doesNotContain("private-host", "ABORTED", "COMMITTED");
    }
  }

  @Test
  void committedStreamsAndUnrelatedErrorsRemainWithTheOriginalHandler() {
    var handler = new GatewayDependencyErrors();
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/agent/runs/one"));
    exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, new Object());
    var bug = new IllegalStateException("application error");
    assertThatThrownBy(() -> handler.handle(exchange, bug).block()).isSameAs(bug);
    exchange.getResponse().setComplete().block();
    assertThatThrownBy(() -> handler.handle(exchange, new ConnectException()).block())
        .hasCauseInstanceOf(ConnectException.class);
  }
}
