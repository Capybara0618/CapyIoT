package io.capyiot.workflow.work;

import io.capyiot.contract.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Human-visible recovery handle. Current authority and device scope are checked on every lookup.
 */
@RestController
class WorkOperationController {
  private final WorkOperations operations;
  private final WorkIdentity users;
  private final WorkOrderService orders;
  private final DeviceQueryPort devices;
  private final WorkOrderStore store;

  WorkOperationController(
      WorkOperations operations,
      WorkIdentity users,
      WorkOrderService orders,
      DeviceQueryPort devices,
      WorkOrderStore store) {
    this.operations = operations;
    this.users = users;
    this.orders = orders;
    this.devices = devices;
    this.store = store;
  }

  @GetMapping("/api/v1/workorder-operations/{id}")
  ApiResponse<?> status(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, HttpServletRequest req) {
    var actor = users.current(jwt);
    var row = operations.row(id);
    if (!actor.userId().equals(row.get("actor_user_id"))) throw ApiProblem.missing();
    actor.require(
        row.get("operation_type").equals("CREATE") ? "workorder.create" : "workorder.reopen");
    if (row.get("work_order_id") != null)
      orders.detail(actor, ((Number) row.get("work_order_id")).longValue());
    else {
      var ids =
          store.db.queryForList(
              "SELECT device_id FROM work_order_operation_device WHERE operation_id=? ORDER BY"
                  + " device_id",
              String.class,
              id);
      if (ids.isEmpty()) throw ApiProblem.missing();
      devices.requireVisibleDevices(
          actor,
          row.get("operation_type").equals("CREATE") ? "workorder.create" : "workorder.reopen",
          ids);
    }
    return ApiResponse.ok(operations.result(id), req.getRequestId());
  }
}
