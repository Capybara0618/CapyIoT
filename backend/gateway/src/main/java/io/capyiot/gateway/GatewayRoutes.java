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
            @Value("${capyiot.services.device-url}") String deviceUrl) {
        return builder.routes()
                .route("device-health", route -> route.path("/api/v1/health")
                        .filters(filters -> filters.setPath("/internal/health"))
                        .uri(deviceUrl))
                .build();
    }
}
