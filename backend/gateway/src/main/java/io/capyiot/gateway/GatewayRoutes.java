package io.capyiot.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration
class GatewayRoutes {
  @Bean
  RouteLocator routes(
      RouteLocatorBuilder builder,
      @Value("${capyiot.services.device-url}") String deviceUrl,
      @Value("${capyiot.services.platform-url}") String platformUrl,
      @Value("${capyiot.services.agent-url}") String agentUrl,
      @Value("${capyiot.services.alarm-url}") String alarmUrl,
      @Value("${capyiot.services.workflow-url}") String workflowUrl) {
    return builder
        .routes()
        .route(
            "foundation-api",
            route ->
                route
                    .path(
                        "/api/v1/auth/me",
                        "/api/v1/auth/logout",
                        "/api/v1/users",
                        "/api/v1/users/**",
                        "/api/v1/grants/**",
                        "/api/v1/audit-records")
                    .uri(platformUrl))
        .route(
            "agent-stream",
            route ->
                route
                    .path("/api/v1/agent/conversations/*/messages")
                    .and()
                    .method(HttpMethod.POST)
                    .metadata("response-timeout", -1)
                    .uri(agentUrl))
        .route("agent-api", route -> route.path("/api/v1/agent/**").uri(agentUrl))
        .route(
            "device-openapi",
            route ->
                route
                    .path("/api/v1/openapi/device")
                    .filters(f -> f.setPath("/v3/api-docs"))
                    .uri(deviceUrl))
        .route(
            "fault-openapi",
            route ->
                route
                    .path("/api/v1/openapi/fault")
                    .filters(f -> f.setPath("/v3/api-docs"))
                    .uri(alarmUrl))
        .route(
            "workflow-openapi",
            route ->
                route
                    .path("/api/v1/openapi/workflow")
                    .filters(f -> f.setPath("/v3/api-docs"))
                    .uri(workflowUrl))
        .route(
            "platform-openapi",
            route ->
                route
                    .path("/api/v1/openapi/platform")
                    .filters(f -> f.setPath("/v3/api-docs"))
                    .uri(platformUrl))
        .route(
            "historical-workorders",
            route ->
                route
                    .path("/api/v1/legacy/workorders", "/api/v1/legacy/workorders/**")
                    .uri(workflowUrl))
        .route(
            "historical-events",
            route -> route.path("/api/v1/legacy/events", "/api/v1/legacy/events/**").uri(deviceUrl))
        .route(
            "legacy-device-history",
            route ->
                route.path("/api/v1/devices/activity", "/api/v1/devices/cursor").uri(deviceUrl))
        .route(
            "device-assets",
            route ->
                route
                    .path(
                        "/api/v1/parks",
                        "/api/v1/areas",
                        "/api/v1/areas/**",
                        "/api/v1/monitoring-points",
                        "/api/v1/monitoring-points/**",
                        "/api/v1/monitoring-point-bindings/**",
                        "/api/v1/network-dependencies",
                        "/api/v1/network-dependencies/**",
                        "/api/v1/power-dependencies",
                        "/api/v1/power-dependencies/**",
                        "/api/v1/video-channel-associations",
                        "/api/v1/video-channel-associations/**",
                        "/api/v1/topology/context",
                        "/api/v1/devices/*/status-history",
                        "/api/v1/device-sessions",
                        "/api/v1/devices/*/credential/rotate",
                        "/api/v1/devices",
                        "/api/v1/devices/*",
                        "/api/v1/devices/*/events",
                        "/api/v1/locations",
                        "/api/v1/events",
                        "/api/v1/events/**",
                        "/api/v1/health")
                    .uri(deviceUrl))
        .route(
            "faults",
            route ->
                route
                    .path(
                        "/api/v1/anomalies",
                        "/api/v1/anomalies/**",
                        "/api/v1/incidents",
                        "/api/v1/incidents/**")
                    .uri(alarmUrl))
        .route(
            "workorders",
            route ->
                route
                    .path(
                        "/api/v1/workorders",
                        "/api/v1/workorders/**",
                        "/api/v1/workorder-assignees",
                        "/api/v1/workorder-operations/**",
                        "/api/v1/devices/*/maintenance-history")
                    .uri(workflowUrl))
        .route(
            "historical-alarms",
            route ->
                route
                    .path("/api/v1/alarms", "/api/v1/alarms/**", "/api/v1/alarm-rules")
                    .uri(alarmUrl))
        .route(
            "device-history-and-scenarios",
            route ->
                route
                    .path(
                        "/api/v1/devices/**",
                        "/api/v1/locations/**",
                        "/api/v1/topology/**",
                        "/api/v1/scenarios/templates",
                        "/api/v1/scenario-runs",
                        "/api/v1/scenario-runs/**")
                    .uri(deviceUrl))
        .build();
  }
}
