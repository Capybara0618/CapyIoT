package io.capyiot.service.runtime;

import io.capyiot.contract.RuntimeGate;
import javax.sql.DataSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
public class RuntimeAutoConfiguration {
    @Bean HealthIndicator runtimeHealthIndicator() {
        return () -> RuntimeGate.draining() ? Health.outOfService().build() : Health.up().build();
    }
    @Bean @ConditionalOnBean(DataSource.class)
    HealthIndicator localDatabaseHealthIndicator(DataSource dataSource) {
        return () -> {
            try (var connection = dataSource.getConnection()) {
                if (!connection.isValid(2)) return Health.down().build();
                String expected = System.getenv("CAPYIOT_SCHEMA_VERSION");
                if (expected != null) {
                    try (var statement = connection.prepareStatement("SELECT version FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank DESC LIMIT 1");
                         var rows = statement.executeQuery()) {
                        if (!rows.next() || !expected.equals(rows.getString(1))) return Health.down().build();
                    }
                }
                return Health.up().build();
            } catch (Exception unavailable) { return Health.down().build(); }
        };
    }
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletRuntime {
        @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
        jakarta.servlet.Filter drainFilter() {
            return (request, response, chain) -> {
                var req = (jakarta.servlet.http.HttpServletRequest) request;
                var res = (jakarta.servlet.http.HttpServletResponse) response;
                if (RuntimeGate.draining() && (req.getRequestURI().startsWith("/api/") || req.getRequestURI().startsWith("/internal/"))) {
                    res.setStatus(503); res.setHeader("Retry-After", "5");
                    res.setHeader("Connection", "close"); return;
                }
                res.setHeader("X-Service-Instance", System.getenv().getOrDefault("HOSTNAME", "local"));
                chain.doFilter(request, response);
            };
        }
    }
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class ReactiveRuntime {
        @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
        org.springframework.web.server.WebFilter drainFilter() {
            return (exchange, chain) -> {
                String path = exchange.getRequest().getPath().value();
                if (RuntimeGate.draining() && (path.startsWith("/api/") || path.startsWith("/internal/"))) {
                    exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                    exchange.getResponse().getHeaders().set("Retry-After", "5");
                    exchange.getResponse().getHeaders().set("Connection", "close");
                    return exchange.getResponse().setComplete();
                }
                exchange.getResponse().getHeaders().set("X-Gateway-Instance", System.getenv().getOrDefault("HOSTNAME", "local"));
                return chain.filter(exchange);
            };
        }
    }
}
