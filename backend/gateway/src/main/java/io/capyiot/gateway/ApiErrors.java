package io.capyiot.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

final class ApiErrors {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ApiErrors() {}

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] body = JSON.writeValueAsBytes(Map.of(
                    "code", code, "message", message, "details", Map.of(),
                    "traceId", exchange.getRequest().getId()));
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        } catch (JsonProcessingException error) {
            return Mono.error(error);
        }
    }
}
