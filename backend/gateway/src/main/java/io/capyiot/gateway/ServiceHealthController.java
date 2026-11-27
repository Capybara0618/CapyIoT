package io.capyiot.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.capyiot.contract.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
class ServiceHealthController {
  record ServiceStatus(String service, String status) {}

  record Snapshot(List<ServiceStatus> services, Instant checkedAt) {}

  private record Probe(String name, WebClient client, String path) {}

  private final List<Probe> probes;

  ServiceHealthController(
      WebClient.Builder builder,
      PlatformWebClientFactory clients,
      @Value("${capyiot.services.ingestion-url}") String ingestion,
      @Value("${capyiot.services.device-url}") String device,
      @Value("${capyiot.services.alarm-url}") String alarm,
      @Value("${capyiot.services.workflow-url}") String workflow,
      @Value("${capyiot.services.platform-url}") String platform,
      @Value("${capyiot.services.agent-url}") String agent) {
    probes =
        List.of(
            new Probe(
                "device-ingestion",
                clients.create(builder, ingestion),
                "/actuator/health/readiness"),
            new Probe(
                "device-service", clients.create(builder, device), "/actuator/health/readiness"),
            new Probe(
                "alarm-service", clients.create(builder, alarm), "/actuator/health/readiness"),
            new Probe(
                "workflow-service",
                clients.create(builder, workflow),
                "/actuator/health/readiness"),
            new Probe(
                "platform-service",
                clients.create(builder, platform),
                "/actuator/health/readiness"),
            new Probe("agent-service", clients.create(builder, agent), "/health/ready"));
  }

  @GetMapping("/api/v1/observability/services")
  Mono<ApiResponse<Snapshot>> services() {
    return Flux.fromIterable(probes)
        .flatMapSequential(this::check)
        .startWith(new ServiceStatus("gateway", "UP"))
        .collectList()
        .map(
            statuses ->
                ApiResponse.ok(
                    new Snapshot(statuses, Instant.now()), UUID.randomUUID().toString()));
  }

  private Mono<ServiceStatus> check(Probe probe) {
    return probe
        .client()
        .get()
        .uri(probe.path())
        .exchangeToMono(
            response ->
                response
                    .bodyToMono(JsonNode.class)
                    .map(
                        body ->
                            new ServiceStatus(
                                probe.name(),
                                response.statusCode().is2xxSuccessful()
                                        && body.path("status").asText().equals("UP")
                                    ? "UP"
                                    : "DOWN")))
        .timeout(Duration.ofSeconds(3))
        .onErrorReturn(new ServiceStatus(probe.name(), "UNREACHABLE"))
        .defaultIfEmpty(new ServiceStatus(probe.name(), "UNREACHABLE"));
  }
}
