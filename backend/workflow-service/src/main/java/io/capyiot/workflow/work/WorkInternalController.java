package io.capyiot.workflow.work;

import io.capyiot.contract.ApiResponse;
import io.capyiot.contract.internal.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
class WorkInternalController {
  private final WorkFacts facts;
  private final WorkOperations operations;

  WorkInternalController(WorkFacts facts, WorkOperations operations) {
    this.facts = facts;
    this.operations = operations;
  }

  private <T> ApiResponse<T> ok(T value, HttpServletRequest req) {
    return ApiResponse.ok(value, req.getRequestId());
  }

  @GetMapping("/internal/v1/operations/{id}")
  ApiResponse<?> operation(@PathVariable String id, HttpServletRequest req) {
    return ok(operations.result(id), req);
  }

  @GetMapping("/internal/v1/incidents/{id}/order-state")
  ApiResponse<?> incident(@PathVariable String id, HttpServletRequest req) {
    return ok(facts.incident(id, null), req);
  }

  @PostMapping("/internal/v1/incidents/{id}/order-state-page")
  ApiResponse<?> page(
      @PathVariable String id,
      @RequestBody WorkOrderApi.IncidentOrdersPageRequest body,
      HttpServletRequest req) {
    return ok(facts.incident(id, body.cursor()), req);
  }

  @PostMapping("/internal/v1/assignments/scope-facts")
  ApiResponse<?> assigned(
      @RequestBody WorkOrderApi.AssignmentRequest body, HttpServletRequest req) {
    return ok(facts.assigned(body), req);
  }

  @GetMapping("/internal/v1/reservations/{id}/operation")
  ApiResponse<?> legacy(@PathVariable String id, HttpServletRequest req) {
    return ok(operations.legacy(id), req);
  }
}
