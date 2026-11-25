package io.capyiot.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.capyiot.contract.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
class MonitoringController {
    private static final Set<String> SERVICES = Set.of("core-service","gateway", "device-ingestion", "device-service", "alarm-service", "workflow-service");
    private static final Set<Integer> WINDOWS = Set.of(15, 60, 360);
    record Point(long at, Double value) {}
    record Series(String id, List<Point> points) {}
    record Snapshot(List<Series> series, boolean prometheusAvailable, Instant checkedAt) {}
    private record Query(String id, String expression) {}

    private final WebClient prometheus;
    private final boolean enabled;
    private final KubernetesStatus kubernetes;

    MonitoringController(WebClient.Builder builder, KubernetesStatus kubernetes,
            @Value("${PROMETHEUS_URL:}") String url) {
        this.kubernetes = kubernetes;
        this.enabled = !url.isBlank();
        this.prometheus = builder.clone().baseUrl(url).build();
    }

    @GetMapping("/api/v1/observability/metrics")
    Mono<ApiResponse<Snapshot>> metrics(@RequestParam(defaultValue = "60") int minutes,
            @RequestParam(defaultValue = "gateway") String service) {
        if (!WINDOWS.contains(minutes) || !SERVICES.contains(service))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的监控服务或时间范围");
        long end = Instant.now().getEpochSecond() / 15 * 15;
        if (!enabled) return Mono.just(ok(new Snapshot(List.of(), false, Instant.ofEpochSecond(end))));
        String selected = "service=\"" + service + "\"";
        var queries = List.of(
                new Query("lag", "max(kafka_consumer_fetch_manager_records_lag_max{service=\"core-service\"})"),
                new Query("tps", "sum(rate(capyiot_device_events_total{result=\"APPLIED\"}[2m]))"),
                new Query("httpP95", "1000 * histogram_quantile(0.95,sum by(le) (rate(http_server_requests_seconds_bucket{"
                        + selected + ",uri!~\"/actuator/.*|/api/v1/stream|/api/v1/.*/stream\"}[5m])))"),
                new Query("heapMiB", "sum(jvm_memory_used_bytes{area=\"heap\"," + selected + "}) / 1048576"),
                new Query("gcPause", "1000 * sum(rate(jvm_gc_pause_seconds_sum{" + selected + "}[5m]))"),
                new Query("poolActive", "sum(hikaricp_connections_active{" + selected + "})"),
                new Query("mysqlP95", "1000 * histogram_quantile(0.95,sum by(le) (rate(capyiot_query_seconds_bucket{" + selected + "}[5m])))"),
                new Query("cacheHit", "100 * sum(rate(capyiot_query_cache_total{result=\"hit\"}[5m])) / sum(rate(capyiot_query_cache_total[5m]))")
        );
        return Flux.fromIterable(queries).flatMapSequential(query -> range(query, end, minutes), 4).collectList()
                .map(series -> new Snapshot(series, true, Instant.ofEpochSecond(end)))
                .timeout(Duration.ofSeconds(4))
                .onErrorReturn(new Snapshot(List.of(), false, Instant.ofEpochSecond(end)))
                .map(MonitoringController::ok);
    }

    @GetMapping("/api/v1/observability/deployment")
    Mono<ApiResponse<KubernetesStatus.Snapshot>> deployment() {
        return kubernetes.read().map(MonitoringController::ok);
    }

    private Mono<Series> range(Query query, long end, int minutes) {
        return prometheus.get().uri(builder -> builder.path("/api/v1/query_range")
                        .queryParam("query", "{expression}").queryParam("start", end - minutes * 60)
                        .queryParam("end", end).queryParam("step", Math.max(15, minutes * 60 / 120))
                        .build(java.util.Map.of("expression", query.expression())))
                .retrieve().bodyToMono(JsonNode.class).map(body -> {
                    if (!"success".equals(body.path("status").asText())) throw new IllegalStateException("Prometheus query failed");
                    var values = body.path("data").path("result").path(0).path("values");
                    var points = new ArrayList<Point>();
                    for (var sample : values) {
                        Double value;
                        try {
                            double parsed = Double.parseDouble(sample.path(1).asText());
                            value = Double.isFinite(parsed) ? parsed : null;
                        } catch (NumberFormatException missing) {
                            value = null;
                        }
                        points.add(new Point(sample.path(0).asLong(), value));
                    }
                    return new Series(query.id(), points);
                });
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.ok(data, UUID.randomUUID().toString());
    }
}
