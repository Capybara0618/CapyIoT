package io.capyiot.workflow.work;

import io.capyiot.contract.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.Hidden
class HistoricalWorkController {
  private final WorkOrderStore store;
  private final DeviceQueryPort devices;
  private final WorkIdentity users;

  HistoricalWorkController(WorkOrderStore store, DeviceQueryPort devices, WorkIdentity users) {
    this.store = store;
    this.devices = devices;
    this.users = users;
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var result = Views.row(row);
    result
        .keySet()
        .retainAll(
            Set.of(
                "id",
                "workOrderNo",
                "campusId",
                "title",
                "priority",
                "status",
                "createdBy",
                "createdAt",
                "updatedAt",
                "sourceType",
                "sourceAlarmId",
                "version"));
    result.put("readOnly", true);
    return result;
  }

  private boolean visible(AuthorizationContext actor, long order) {
    var rows =
        store.db.queryForList(
            "SELECT device_id FROM work_order_device_item WHERE work_order_id=?",
            String.class,
            order);
    if (rows.isEmpty()) {
      rows =
          store.db.queryForList(
              "SELECT JSON_UNQUOTE(JSON_EXTRACT(s.snapshot_json,'$.deviceId')) FROM"
                  + " workorder_alarm_source s JOIN work_order w ON w.campus_id=s.campus_id AND"
                  + " w.source_alarm_id=s.alarm_id WHERE w.id=?",
              String.class,
              order);
    }
    if (rows.isEmpty() || rows.stream().anyMatch(Objects::isNull)) return false;
    try {
      devices.requireVisibleDevices(actor, "workorder.read", rows);
      return true;
    } catch (ApiProblem hidden) {
      return false;
    }
  }

  @GetMapping("/api/v1/legacy/workorders")
  ApiResponse<?> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest req) {
    var actor = users.current(jwt);
    actor.require("workorder.read");
    var rows =
        store
            .db
            .queryForList(
                "SELECT * FROM work_order WHERE source_type='LEGACY_ALARM' ORDER BY updated_at"
                    + " DESC,id DESC")
            .stream()
            .filter(r -> visible(actor, ((Number) r.get("id")).longValue()))
            .map(this::view)
            .toList();
    return ApiResponse.ok(Views.page(rows, page, size), req.getRequestId());
  }

  @GetMapping("/api/v1/legacy/workorders/{id}")
  ApiResponse<?> detail(
      @AuthenticationPrincipal Jwt jwt, @PathVariable long id, HttpServletRequest req) {
    var actor = users.current(jwt);
    var rows =
        store.db.queryForList(
            "SELECT * FROM work_order WHERE id=? AND source_type='LEGACY_ALARM'", id);
    if (rows.isEmpty() || !visible(actor, id)) throw ApiProblem.missing();
    var result = view(rows.getFirst());
    result.put(
        "statusHistory",
        store
            .db
            .queryForList(
                "SELECT order_version,action,from_status,to_status,actor,remark,created_at FROM"
                    + " work_order_history WHERE order_id=? ORDER BY order_version",
                id)
            .stream()
            .map(Views::row)
            .toList());
    return ApiResponse.ok(result, req.getRequestId());
  }

  @GetMapping("/api/v1/workorders/stream")
  void stream() {
    throw new ApiProblem(410, "LEGACY_FEATURE_RETIRED", "旧工单流已退出，请查询当前维修工单状态");
  }
}
