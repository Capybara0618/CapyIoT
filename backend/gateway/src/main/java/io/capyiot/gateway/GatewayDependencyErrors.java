package io.capyiot.gateway;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

/** Classify proxy transport failures without replaying a command or inventing its outcome. */
@Component
@Order(-2)
final class GatewayDependencyErrors implements WebExceptionHandler {
  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
    if (exchange.getResponse().isCommitted()
        || exchange.getAttribute(GATEWAY_ROUTE_ATTR) == null
        || !exchange.getRequest().getPath().value().startsWith("/api/v1/")
        || !transportFailure(error)) return Mono.error(error);
    boolean query = exchange.getRequest().getMethod() == HttpMethod.GET;
    return ApiErrors.write(
        exchange,
        HttpStatus.SERVICE_UNAVAILABLE,
        "DEPENDENCY_UNAVAILABLE",
        query ? "依赖服务暂不可用，请稍后重试" : "依赖服务暂不可用，请先核对原操作结果，再使用原请求重试");
  }

  private boolean transportFailure(Throwable error) {
    for (int depth = 0; error != null && depth < 16; depth++, error = error.getCause()) {
      if (error instanceof ConnectException
          || error instanceof UnknownHostException
          || error instanceof TimeoutException
          || error instanceof io.netty.handler.timeout.TimeoutException
          || error instanceof SSLException
          || error instanceof PrematureCloseException) return true;
    }
    return false;
  }
}
