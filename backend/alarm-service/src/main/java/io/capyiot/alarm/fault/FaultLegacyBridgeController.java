package io.capyiot.alarm.fault;

import io.capyiot.contract.internal.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Expiring Core migration bridge. It never proves a rollback on behalf of the old caller. */
@RestController
@io.swagger.v3.oas.annotations.Hidden
class FaultLegacyBridgeController {
  private final FaultGate gate;
  private final FaultIdentity users;
  private final FaultService faults;

  FaultLegacyBridgeController(FaultGate gate, FaultIdentity users, FaultService faults) {
    this.gate = gate;
    this.users = users;
    this.faults = faults;
  }

  record Reference(String incidentId, String permission) {}

  @PostMapping("/internal/fault-bridge/reference")
  FaultApi.IncidentRef reference(@AuthenticationPrincipal Jwt jwt, @RequestBody Reference body) {
    var actor = users.current(jwt);
    if (actor.sessionId().startsWith("delegation:")
        && !body.permission().equals("workorder.create")) throw ApiProblem.forbidden();
    return gate.reference(actor, body.incidentId(), body.permission());
  }

  @PostMapping("/internal/fault-bridge/evidence")
  FaultApi.EvidenceResult evidence(
      @AuthenticationPrincipal Jwt jwt, @RequestBody FaultApi.EvidenceRequest body) {
    boolean visible =
        faults.visibleEvidence(
            users.current(jwt), body.evidenceReferenceId(), body.workOrderDeviceIds());
    return new FaultApi.EvidenceResult(
        visible, visible, visible ? body.evidenceReferenceId() : null, Instant.now());
  }

  @PostMapping("/internal/fault-bridge/reserve")
  FaultApi.Reservation reserve(
      @AuthenticationPrincipal Jwt jwt, @RequestBody FaultApi.Reserve body) {
    return gate.reserve(users.current(jwt), body.binding().incidentId(), body.binding());
  }

  record CommitNotice(String reservationId, String workOrderId) {}

  @PostMapping("/internal/fault-bridge/commit")
  void commit(@RequestBody CommitNotice body) {
    gate.legacyCommit(body.reservationId(), body.workOrderId());
  }

  record Uncertain(String reservationId) {}

  @PostMapping("/internal/fault-bridge/uncertain")
  void uncertain(@RequestBody Uncertain body) {
    gate.markUncertain(body.reservationId());
  }
}
