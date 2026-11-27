package io.capyiot.alarm.fault;

import static io.capyiot.alarm.fault.FaultModels.*;

import io.capyiot.contract.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name = "capyiot.faults.enabled", havingValue = "true")
class FaultController {
  private final FaultService faults;
  private final FaultIdentity platform;

  FaultController(FaultService faults, FaultIdentity platform) {
    this.faults = faults;
    this.platform = platform;
  }

  private <T> ApiResponse<T> ok(T body, HttpServletRequest request) {
    return ApiResponse.ok(body, request.getRequestId());
  }

  @GetMapping("/anomalies")
  ApiResponse<?> anomalies(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String deviceId,
      @RequestParam(required = false) String anomalyType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest req) {
    return ok(
        faults.anomalies(
            platform.current(jwt), deviceId, anomalyType, severity, status, from, to, page, size),
        req);
  }

  @GetMapping("/anomalies/{id}")
  ApiResponse<?> anomaly(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, HttpServletRequest req) {
    return ok(faults.anomalyDetail(platform.current(jwt), id), req);
  }

  @PostMapping("/anomalies/{id}/dismiss")
  ApiResponse<?> dismiss(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Reason body,
      HttpServletRequest req) {
    return ok(faults.dismiss(platform.current(jwt), key, id, body), req);
  }

  @GetMapping("/incidents")
  ApiResponse<?> incidents(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Priority priority,
      @RequestParam(required = false) String deviceId,
      @RequestParam(required = false) String areaId,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest req) {
    return ok(
        faults.incidents(
            platform.current(jwt),
            status,
            priority == null ? null : priority.name(),
            deviceId,
            areaId,
            from,
            to,
            page,
            size),
        req);
  }

  @GetMapping("/incidents/{id}")
  ApiResponse<?> incident(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, HttpServletRequest req) {
    return ok(faults.detail(platform.current(jwt), id), req);
  }

  @PostMapping("/incidents")
  org.springframework.http.ResponseEntity<?> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Create body,
      HttpServletRequest req) {
    var result = faults.create(platform.current(jwt), key, body);
    return org.springframework.http.ResponseEntity.status(
            result.path("created").asBoolean() ? 201 : 200)
        .body(ok(result, req));
  }

  @PostMapping("/incidents/{id}/review")
  ApiResponse<?> review(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Review body,
      HttpServletRequest req) {
    return ok(faults.review(platform.current(jwt), key, id, body), req);
  }

  @PatchMapping("/incidents/{id}/priority")
  ApiResponse<?> priority(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ChangePriority body,
      HttpServletRequest req) {
    return ok(faults.priority(platform.current(jwt), key, id, body), req);
  }

  @PatchMapping("/incidents/{id}/root-cause")
  ApiResponse<?> cause(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Cause body,
      HttpServletRequest req) {
    return ok(faults.cause(platform.current(jwt), key, id, body), req);
  }

  @PostMapping("/incidents/{id}/close")
  ApiResponse<?> close(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Close body,
      HttpServletRequest req) {
    return ok(faults.close(platform.current(jwt), key, id, body), req);
  }
}
