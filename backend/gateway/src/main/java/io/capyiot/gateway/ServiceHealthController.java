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
    private record Probe(String name, String url) {}

    private final WebClient http;
    private final List<Probe> probes;

    ServiceHealthController(WebClient.Builder builder,
            @Value("${capyiot.services.ingestion-url}") String ingestion,
            @Value("${capyiot.services.device-url}") String device,
            @Value("${capyiot.services.alarm-url}") String alarm,
            @Value("${capyiot.services.workflow-url}") String workflow,@Value("${capyiot.services.core-url}") String core) {
        http = builder.build();
        probes = List.of(new Probe("device-ingestion", ingestion), new Probe("device-service", device),
                new Probe("alarm-service", alarm), new Probe("workflow-service", workflow),new Probe("core-service",core));
    }

    @GetMapping("/api/v1/observability/services")
    Mono<ApiResponse<Snapshot>> services() {
        return Flux.fromIterable(probes).flatMapSequential(this::check)
                .startWith(new ServiceStatus("gateway", "UP")).collectList()
                .map(statuses -> ApiResponse.ok(new Snapshot(statuses, Instant.now()), UUID.randomUUID().toString()));
    }

    private Mono<ServiceStatus> check(Probe probe) {
        return http.get().uri(probe.url() + "/actuator/health/readiness").exchangeToMono(response ->
                response.bodyToMono(JsonNode.class).map(body -> new ServiceStatus(probe.name(),
                        response.statusCode().is2xxSuccessful() && body.path("status").asText().equals("UP") ? "UP" : "DOWN")))
                .timeout(Duration.ofSeconds(3))
                .onErrorReturn(new ServiceStatus(probe.name(), "UNREACHABLE"))
                .defaultIfEmpty(new ServiceStatus(probe.name(), "UNREACHABLE"));
    }
}
