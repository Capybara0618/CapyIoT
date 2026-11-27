package io.capyiot.gateway;

import io.capyiot.contract.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Retired event streams cannot silently fall through to broad business routes. */
@RestController
class CompatibilityEndpoints {
  @GetMapping({"/api/v1/stream", "/api/v1/alarms/stream", "/api/v1/workorders/stream"})
  void retiredStream() {
    throw new ResponseStatusException(HttpStatus.GONE, "旧事件流已停用；请刷新业务列表查看当前状态");
  }

  @GetMapping("/api/v1/openapi")
  ApiResponse<Map<String, Object>> catalog() {
    return ApiResponse.ok(
        Map.of(
            "services",
            List.of(
                Map.of("service", "device-service", "url", "/api/v1/openapi/device"),
                Map.of("service", "alarm-service", "url", "/api/v1/openapi/fault"),
                Map.of("service", "workflow-service", "url", "/api/v1/openapi/workflow"),
                Map.of("service", "platform-service", "url", "/api/v1/openapi/platform"),
                Map.of("service", "agent-service", "url", "/api/v1/agent/openapi")),
            "historicalOwners",
            Map.of("alarms", "alarm-service", "workorders", "workflow-service")),
        UUID.randomUUID().toString());
  }
}
