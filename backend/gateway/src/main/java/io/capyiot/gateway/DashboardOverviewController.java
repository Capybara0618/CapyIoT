package io.capyiot.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.capyiot.contract.ApiResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
class DashboardOverviewController {
  record Overview(JsonNode devices, JsonNode alarms, JsonNode workorders) {}

  private final WebClient devices;
  private final WebClient alarms;
  private final WebClient workflow;

  DashboardOverviewController(
      WebClient.Builder builder,
      PlatformWebClientFactory clients,
      @Value("${capyiot.services.device-url}") String deviceUrl,
      @Value("${capyiot.services.alarm-url}") String alarmUrl,
      @Value("${capyiot.services.workflow-url}") String workflowUrl) {
    devices = clients.create(builder, deviceUrl);
    alarms = clients.create(builder, alarmUrl);
    workflow = clients.create(builder, workflowUrl);
  }

  @GetMapping("/api/v1/dashboard/overview")
  Mono<ApiResponse<Overview>> overview(@AuthenticationPrincipal Jwt jwt) {
    return Mono.zip(
            get(devices, "/api/v1/devices/summary", jwt),
            get(alarms, "/api/v1/incidents?size=1", jwt),
            get(workflow, "/api/v1/workorders?size=1", jwt))
        .map(
            parts ->
                ApiResponse.ok(
                    new Overview(parts.getT1(), parts.getT2(), parts.getT3()),
                    UUID.randomUUID().toString()));
  }

  private Mono<JsonNode> get(WebClient service, String path, Jwt jwt) {
    return service
        .get()
        .uri(path)
        .headers(headers -> headers.setBearerAuth(jwt.getTokenValue()))
        .retrieve()
        .bodyToMono(JsonNode.class)
        .map(response -> response.required("data"))
        .timeout(Duration.ofSeconds(5))
        .onErrorMap(
            error ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法获取驾驶舱数据", error));
  }
}
