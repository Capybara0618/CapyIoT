package io.capyiot.alarm;

import io.capyiot.contract.TopologyEvent;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class TopologySync {
    private static final Logger LOG = LoggerFactory.getLogger(TopologySync.class);
    private final RestClient device;
    private final TopologyProjection projection;
    private final String demoCampus;

    TopologySync(TopologyProjection projection, RestClient.Builder builder,
            @Value("${capyiot.device-service-url}") String deviceUrl,
            @Value("${capyiot.topology-sync-token}") String token,
            @Value("${capyiot.demo-campus:CAMPUS-A}") String demoCampus) {
        this.projection = projection;
        this.demoCampus = demoCampus;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.device = builder.baseUrl(deviceUrl).requestFactory(factory)
                .defaultHeader("X-Topology-Token", token).build();
    }

    @Scheduled(fixedDelay = 15000)
    void initializeDemoCampus() {
        if (projection.version(demoCampus) >= 0) return;
        try {
            refresh(demoCampus, 0);
        } catch (RuntimeException failure) {
            LOG.warn("Initial alarm topology will retry: {}", failure.getClass().getSimpleName());
        }
    }

    void ensure(String campus, long requiredVersion) {
        if (projection.version(campus) < requiredVersion) refresh(campus, requiredVersion);
    }

    void refresh(String campus, long requiredVersion) {
        var nodes = new ArrayList<TopologyEvent.Node>();
        Long version = null;
        String after = "";
        do {
            String cursor = after;
            Long expected = version;
            var page = device.get().uri(builder -> {
                var uri = builder.path("/internal/topology").queryParam("campusId", campus).queryParam("after", cursor);
                if (expected != null) uri.queryParam("version", expected);
                return uri.build();
            }).retrieve().body(TopologyEvent.Page.class);
            if (page == null || page.version() < requiredVersion || (version != null && page.version() != version)) {
                throw new IllegalStateException("Incomplete topology snapshot");
            }
            version = page.version();
            nodes.addAll(page.nodes());
            after = page.nextAfter();
            if (after != null && after.compareTo(cursor) <= 0) throw new IllegalStateException("Invalid topology cursor");
        } while (after != null);
        projection.replace(campus, version, nodes);
    }
}
