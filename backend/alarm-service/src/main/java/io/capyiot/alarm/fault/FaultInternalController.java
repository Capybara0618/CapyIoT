package io.capyiot.alarm.fault;

import io.capyiot.contract.ApiResponse;
import io.capyiot.contract.internal.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class FaultInternalController {
  private final FaultGate gate;
  private final FaultService faults;
  private final FaultIdentity users;
  private final FaultStore store;
  private final FaultProgress progress;

  FaultInternalController(
      FaultGate gate,
      FaultService faults,
      FaultIdentity users,
      FaultStore store,
      FaultProgress progress) {
    this.gate = gate;
    this.faults = faults;
    this.users = users;
    this.store = store;
    this.progress = progress;
  }

  private <T> ApiResponse<T> ok(T result, HttpServletRequest req) {
    return ApiResponse.ok(result, req.getRequestId());
  }

  @PostMapping("/internal/v1/health/batch")
  ApiResponse<?> health(@RequestBody DeviceApi.Batch body, HttpServletRequest req) {
    // Never call platform/device/workflow here: this service-only query cannot form an
    // authorization cycle.
    var result =
        store.read.execute(
            s -> {
              var states = faults.deviceHealth(Set.copyOf(body.deviceIds()));
              var values =
                  body.deviceIds().stream()
                      .map(
                          id -> {
                            var versions =
                                store.db.queryForList(
                                    "SELECT version FROM fault_health_version WHERE device_id=?",
                                    Long.class,
                                    id);
                            return new FaultApi.Health(
                                id,
                                states.getOrDefault(id, "UNKNOWN"),
                                versions.isEmpty() ? 0 : versions.getFirst());
                          })
                      .toList();
              return new FaultApi.HealthResult(values, Instant.now(), progress.healthComplete());
            });
    return ok(result, req);
  }

  @PostMapping("/internal/v1/evidence/resolve")
  ApiResponse<?> evidence(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody FaultApi.EvidenceRequest body,
      HttpServletRequest req) {
    boolean visible =
        faults.visibleEvidence(
            users.current(jwt), body.evidenceReferenceId(), body.workOrderDeviceIds());
    return ok(
        new FaultApi.EvidenceResult(
            visible, visible, visible ? body.evidenceReferenceId() : null, Instant.now()),
        req);
  }

  @GetMapping("/internal/v1/incidents/{id}")
  ApiResponse<?> reference(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, HttpServletRequest req) {
    return ok(gate.reference(users.current(jwt), id, "workorder.create"), req);
  }

  @PostMapping("/internal/v1/incidents/{id}/reservations")
  ApiResponse<?> reserve(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestBody FaultApi.Reserve body,
      HttpServletRequest req) {
    return ok(gate.reserve(users.current(jwt), id, body.binding()), req);
  }

  @GetMapping("/internal/v1/operations/{id}/reservation")
  ApiResponse<?> query(@PathVariable String id, HttpServletRequest req) {
    return ok(gate.query(id), req);
  }

  @PostMapping("/internal/v1/reservations/{id}/commit")
  ApiResponse<?> commit(
      @PathVariable String id, @RequestBody FaultApi.Commit body, HttpServletRequest req) {
    return ok(gate.commit(id, body), req);
  }

  @PostMapping("/internal/v1/reservations/{id}/abort-ack")
  ApiResponse<?> abort(
      @PathVariable String id,
      @RequestBody FaultApi.AbortAcknowledgement body,
      HttpServletRequest req) {
    return ok(gate.abort(id, body), req);
  }
}
