package io.capyiot.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GatewayRoutes {
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder,
            @Value("${capyiot.services.device-url}") String deviceUrl,
            @Value("${capyiot.services.alarm-url}") String alarmUrl,
            @Value("${capyiot.services.workflow-url}") String workflowUrl) {
        return builder.routes()
                .route("device-health", route -> route.path("/api/v1/health")
                        .filters(filters -> filters.setPath("/internal/health"))
                        .uri(deviceUrl))
                .route("device-stream", route -> route.path("/api/v1/stream").metadata("response-timeout", -1).uri(deviceUrl))
                .route("alarm-stream", route -> route.path("/api/v1/alarms/stream").metadata("response-timeout", -1).uri(alarmUrl))
                .route("alarm-api", route -> route.path("/api/v1/alarms", "/api/v1/alarms/**", "/api/v1/alarm-rules").uri(alarmUrl))
                .route("workorder-stream", route -> route.path("/api/v1/workorders/stream")
                        .metadata("response-timeout", -1).uri(workflowUrl))
                .route("workorder-api", route -> route.path("/api/v1/workorders", "/api/v1/workorders/**").uri(workflowUrl))
                .route("device-api", route -> route.path("/api/v1/devices/**", "/api/v1/devices", "/api/v1/locations/**", "/api/v1/locations",
                                "/api/v1/topology/**", "/api/v1/device-sessions", "/api/v1/events/**", "/api/v1/events", "/api/v1/openapi")
                        .uri(deviceUrl))
                .build();
    }
}
