package io.capyiot.device;

import io.capyiot.contract.ApiResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {
    private final HealthEndpoint healthEndpoint;

    HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/internal/health")
    ResponseEntity<ApiResponse<Health>> health() {
        var status = healthEndpoint.health().getStatus();
        var result = new Health("device-service", status.getCode(), Instant.now());
        String traceId = UUID.randomUUID().toString();
        if (!Status.UP.equals(status)) {
            return ResponseEntity.status(503).body(new ApiResponse<>(
                    "SERVICE_UNAVAILABLE", "设备服务依赖暂不可用", result, traceId));
        }
        return ResponseEntity.ok(ApiResponse.ok(result, traceId));
    }

    record Health(String service, String status, Instant checkedAt) {}
}
