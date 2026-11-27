package io.capyiot.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.internal.InternalEndpoint;
import io.capyiot.contract.internal.PlatformApi;
import io.capyiot.contract.internal.ServiceId;
import io.capyiot.service.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/** Two fixed platform contracts. No public request can supply a destination or service identity. */
@Component
class PlatformIdentityClient {
  private final WebClient platform;
  private final ObjectMapper json;
  private final ServiceKeyRing keys;
  private final ServiceTokenSigner signer;

  PlatformIdentityClient(
      WebClient.Builder builder,
      ObjectMapper json,
      PlatformWebClientFactory clients,
      @Value("${capyiot.services.platform-url}") String origin,
      @Value("${capyiot.auth.service-key-file:/run/secrets/gateway-service-keys.json}")
          String path) {
    this.platform = clients.create(builder, origin);
    this.json = json;
    keys = new ServiceKeyRing(Path.of(path));
    signer = new ServiceTokenSigner(ServiceId.GATEWAY, keys, Clock.systemUTC());
  }

  Map<String, Object> jwks() {
    return keys.publicJwks();
  }

  Mono<PlatformApi.SessionIdentity> login(PlatformApi.LoginRequest body, String correlation) {
    return call(
        InternalEndpoint.PLATFORM_LOGIN,
        null,
        body,
        PlatformApi.SessionIdentity.class,
        correlation);
  }

  Mono<PlatformApi.Identity> context(String token, String correlation) {
    return call(
        InternalEndpoint.IDENTITY_CONTEXT,
        ActorCredential.bearer("Bearer " + token),
        new PlatformApi.IdentityRequest(),
        PlatformApi.Identity.class,
        correlation);
  }

  private <T> Mono<T> call(
      InternalEndpoint operation,
      ActorCredential actor,
      Object body,
      Class<T> type,
      String correlation) {
    return Mono.defer(
        () -> {
          var budget = CallBudget.start(Duration.ofSeconds(8), Clock.systemUTC());
          Mono<T> attempt =
              Mono.fromCallable(
                      () -> {
                        byte[] bytes = json.writeValueAsBytes(body);
                        return new SignedBody(
                            bytes, signer.sign(operation, operation.path(), actor, bytes, budget));
                      })
                  .subscribeOn(Schedulers.boundedElastic())
                  .flatMap(
                      signed ->
                          platform
                              .method(HttpMethod.POST)
                              .uri(operation.path())
                              .headers(
                                  headers -> {
                                    headers.set(
                                        InternalEndpoint.SERVICE_AUTHORIZATION,
                                        "Bearer " + signed.token());
                                    headers.set(InternalEndpoint.DEADLINE_HEADER, budget.header());
                                    headers.set("X-Correlation-Id", correlation);
                                    headers.set("X-Request-Id", correlation);
                                    headers.set("Content-Type", "application/json");
                                    if (actor != null)
                                      headers.set("Authorization", actor.authorization());
                                  })
                              .bodyValue(signed.bytes())
                              .exchangeToMono(
                                  response -> {
                                    if (!response.statusCode().is2xxSuccessful()) {
                                      boolean rejected = response.statusCode().value() == 401;
                                      return response
                                          .releaseBody()
                                          .then(
                                              Mono.error(
                                                  new ResponseStatusException(
                                                      rejected
                                                          ? HttpStatus.UNAUTHORIZED
                                                          : HttpStatus.SERVICE_UNAVAILABLE,
                                                      rejected ? "会话失效或账号密码错误" : "平台授权服务暂不可用")));
                                    }
                                    return response
                                        .bodyToMono(byte[].class)
                                        .map(
                                            bytes -> {
                                              try {
                                                var envelope = json.readTree(bytes);
                                                if (!"OK".equals(envelope.path("code").asText())
                                                    || !envelope.hasNonNull("data")
                                                    || !envelope.path("traceId").isTextual())
                                                  throw new IllegalArgumentException();
                                                return json.treeToValue(envelope.get("data"), type);
                                              } catch (Exception error) {
                                                throw new ResponseStatusException(
                                                    HttpStatus.SERVICE_UNAVAILABLE, "平台响应无效");
                                              }
                                            })
                                        .switchIfEmpty(
                                            Mono.error(
                                                new ResponseStatusException(
                                                    HttpStatus.SERVICE_UNAVAILABLE, "平台响应为空")));
                                  })
                              .timeout(budget.remaining(Duration.ofSeconds(3))));
          if (operation.query())
            attempt =
                attempt.retryWhen(
                    Retry.max(1)
                        .filter(
                            error ->
                                !(error instanceof ResponseStatusException status)
                                    || status.getStatusCode().value() >= 500)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
          return attempt
              .timeout(Duration.ofSeconds(8))
              .onErrorMap(
                  error ->
                      error instanceof ResponseStatusException
                          ? error
                          : new ResponseStatusException(
                              HttpStatus.SERVICE_UNAVAILABLE, "平台授权服务暂不可用"));
        });
  }

  private record SignedBody(byte[] bytes, String token) {
    @Override
    public String toString() {
      return "SignedBody[REDACTED]";
    }
  }
}
