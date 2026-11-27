package io.capyiot.workflow.work;

import io.capyiot.contract.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class WorkOutboxController {
  private final WorkOrderStore store;
  private final WorkIdentity users;
  private final DeviceQueryPort devices;

  WorkOutboxController(WorkOrderStore store, WorkIdentity users, DeviceQueryPort devices) {
    this.store = store;
    this.users = users;
    this.devices = devices;
  }

  private boolean allowed(AuthorizationContext actor, Map<String, Object> row) {
    actor.require("workorder.assign");
    String aggregate = row.get("aggregate_id").toString();
    var ids =
        "WorkOrderOperation".equals(row.get("aggregate_type"))
            ? store.db.queryForList(
                "SELECT device_id FROM work_order_operation_device WHERE operation_id=?",
                String.class,
                aggregate)
            : store.db.queryForList(
                "SELECT device_id FROM work_order_device_item WHERE work_order_id=?",
                String.class,
                aggregate);
    if (ids.isEmpty()) return false;
    try {
      devices.requireVisibleDevices(actor, "workorder.assign", ids);
      return true;
    } catch (ApiProblem hidden) {
      return false;
    }
  }

  @GetMapping("/api/v1/workorders/outbox/dead")
  ApiResponse<?> dead(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest req) {
    if (after < 0 || size < 1 || size > 100) throw ApiProblem.invalid("分页无效");
    var actor = users.current(jwt);
    actor.require("workorder.assign");
    var rows =
        store.db.queryForList(
            "SELECT"
                + " id,campus_id,aggregate_type,aggregate_id,event_id,event_type,topic,retry_count,last_error,created_at"
                + " FROM outbox_event WHERE status='DEAD' AND id>? ORDER BY id LIMIT 1001",
            after);
    if (rows.size() > 1000) throw new ApiProblem(503, "OUTBOX_QUERY_LIMIT", "死信数量较多，请使用更具体的游标");
    return ApiResponse.ok(
        rows.stream().filter(r -> allowed(actor, r)).limit(size).map(Views::row).toList(),
        req.getRequestId());
  }

  @PostMapping("/api/v1/workorders/outbox/{id}/retry")
  ApiResponse<?> retry(
      @AuthenticationPrincipal Jwt jwt, @PathVariable long id, HttpServletRequest req) {
    var actor = users.current(jwt);
    var rows = store.db.queryForList("SELECT * FROM outbox_event WHERE id=? AND status='DEAD'", id);
    if (rows.isEmpty() || !allowed(actor, rows.getFirst())) throw ApiProblem.missing();
    if (store.db.update(
            "UPDATE outbox_event SET"
                + " status='RETRY',retry_count=0,next_retry_at=CURRENT_TIMESTAMP(3),retried_by=?,retried_at=CURRENT_TIMESTAMP(3)"
                + " WHERE id=? AND status='DEAD'",
            actor.userId(),
            id)
        != 1) throw ApiProblem.conflict("消息状态已改变");
    return ApiResponse.ok(Map.of("id", id, "status", "RETRY"), req.getRequestId());
  }
}
