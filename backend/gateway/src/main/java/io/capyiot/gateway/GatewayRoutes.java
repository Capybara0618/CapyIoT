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
            @Value("${capyiot.services.core-url}") String coreUrl,
            @Value("${capyiot.services.agent-url}") String agentUrl,
            @Value("${capyiot.services.alarm-url}") String alarmUrl,
            @Value("${capyiot.services.workflow-url}") String workflowUrl) {
        return builder.routes()
                .route("foundation-api",route->route.path("/api/v1/auth/me","/api/v1/auth/logout","/api/v1/users","/api/v1/users/**","/api/v1/grants/**","/api/v1/audit-records").uri(coreUrl))
                .route("agent-api",route->route.path("/api/v1/agent/**").metadata("response-timeout",-1).uri(agentUrl))
                .route("core-openapi",route->route.path("/api/v1/openapi").filters(f->f.setPath("/v3/api-docs")).uri(coreUrl))
                .route("legacy-device-history",route->route.path("/api/v1/devices/activity","/api/v1/devices/cursor").uri(deviceUrl))
                .route("core-assets",route->route.path("/api/v1/parks","/api/v1/areas","/api/v1/areas/**","/api/v1/monitoring-points","/api/v1/monitoring-points/**",
                        "/api/v1/monitoring-point-bindings/**","/api/v1/network-dependencies","/api/v1/network-dependencies/**","/api/v1/power-dependencies","/api/v1/power-dependencies/**",
                        "/api/v1/video-channel-associations","/api/v1/video-channel-associations/**","/api/v1/topology/context","/api/v1/devices/*/status-history",
                        "/api/v1/device-sessions","/api/v1/devices/*/credential/rotate","/api/v1/devices","/api/v1/devices/*","/api/v1/devices/*/events","/api/v1/locations","/api/v1/events","/api/v1/events/**","/api/v1/health").uri(coreUrl))
                .route("core-faults",route->route.path("/api/v1/anomalies","/api/v1/anomalies/**","/api/v1/incidents","/api/v1/incidents/**").uri(coreUrl))
                .route("core-workorders",route->route.path("/api/v1/workorders","/api/v1/workorders/**","/api/v1/workorder-assignees","/api/v1/devices/*/maintenance-history").uri(coreUrl))
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
                                "/api/v1/topology/**", "/api/v1/device-sessions", "/api/v1/events/**", "/api/v1/events", "/api/v1/openapi",
                                "/api/v1/scenarios/templates", "/api/v1/scenario-runs", "/api/v1/scenario-runs/**")
                        .uri(deviceUrl))
                .build();
    }
}
