package io.capyiot.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(WebExchangeBindException.class)
    Mono<Void> validation(WebExchangeBindException error, ServerWebExchange exchange) {
        return ApiErrors.write(exchange, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请填写有效的账号和密码");
    }

    @ExceptionHandler(ResponseStatusException.class)
    Mono<Void> status(ResponseStatusException error, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
        String code = status == HttpStatus.UNAUTHORIZED ? "LOGIN_FAILED" : "REQUEST_FAILED";
        return ApiErrors.write(exchange, status, code, error.getReason());
    }
}
