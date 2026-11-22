package io.capyiot.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

@Component
class KubernetesStatus {
    private static final Path ACCOUNT = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
    private static final List<String> APP_NAMES = List.of("gateway", "device-ingestion", "device-service",
            "alarm-service", "workflow-service", "frontend");
    record Workload(String service, Integer desired, Integer ready, Integer available) {}
    record Scaling(boolean configured, boolean ready, boolean active, Integer min, Integer max) {}
    record Snapshot(boolean k8sEnabled, boolean reachable, List<Workload> workloads, Scaling scaling, Instant checkedAt) {}

    private final boolean enabled;
    private final WebClient api;
    private final String namespace;

    KubernetesStatus(WebClient.Builder builder, @Value("${K8S_ENABLED:false}") boolean enabled) throws IOException {
        this.enabled = enabled;
        if (enabled) {
            namespace = Files.readString(ACCOUNT.resolve("namespace")).trim();
            var tls = SslContextBuilder.forClient().trustManager(ACCOUNT.resolve("ca.crt").toFile()).build();
            api = builder.clone().baseUrl("https://kubernetes.default.svc")
                    .clientConnector(new ReactorClientHttpConnector(HttpClient.create().secure(ssl -> ssl.sslContext(tls))))
                    .build();
        } else {
            namespace = null;
            api = null;
        }
    }

    Mono<Snapshot> read() {
        if (!enabled) return Mono.just(new Snapshot(false, false, List.of(), null, Instant.now()));
        return Mono.fromCallable(() -> Files.readString(ACCOUNT.resolve("token")).trim())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(token -> Mono.zip(
                        Flux.fromIterable(APP_NAMES).flatMapSequential(name -> workload(token, name)).collectList(),
                        scaling(token).defaultIfEmpty(new Scaling(false, false, false, null, null))))
                .map(result -> new Snapshot(true, true, result.getT1(), result.getT2(), Instant.now()))
                .timeout(Duration.ofSeconds(3))
                .onErrorReturn(new Snapshot(true, false, List.of(), null, Instant.now()));
    }

    private Mono<Workload> workload(String token, String name) {
        return get(token, "/apis/apps/v1/namespaces/" + namespace + "/deployments/" + name)
                .map(body -> new Workload(name, body.path("spec").path("replicas").asInt(1),
                        body.path("status").path("readyReplicas").asInt(0),
                        body.path("status").path("availableReplicas").asInt(0)));
    }

    private Mono<Scaling> scaling(String token) {
        return get(token, "/apis/keda.sh/v1alpha1/namespaces/" + namespace + "/scaledobjects/device-service")
                .map(body -> {
                    var spec = body.path("spec");
                    return new Scaling(true, condition(body, "Ready"), condition(body, "Active"),
                            spec.path("minReplicaCount").asInt(1), spec.path("maxReplicaCount").asInt(4));
                });
    }

    private boolean condition(JsonNode node, String type) {
        for (var item : node.path("status").path("conditions")) {
            if (item.path("type").asText().equals(type)) return item.path("status").asText().equals("True");
        }
        return false;
    }

    private Mono<JsonNode> get(String token, String path) {
        return api.get().uri(path).headers(headers -> headers.setBearerAuth(token)).exchangeToMono(response -> {
            if (response.statusCode() == HttpStatus.NOT_FOUND) return Mono.empty();
            if (response.statusCode().isError()) return response.createException().flatMap(Mono::error);
            return response.bodyToMono(JsonNode.class);
        });
    }
}
