package io.capyiot.alarm.fault;

import io.capyiot.contract.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Original Alarm records remain inspectable; no acknowledgement, rule writer, or auto-work-order
 * publisher.
 */
@RestController
@io.swagger.v3.oas.annotations.Hidden
class HistoricalAlarmController {
  private final FaultStore store;
  private final DeviceQueryPort devices;
  private final FaultIdentity users;

  HistoricalAlarmController(FaultStore store, DeviceQueryPort devices, FaultIdentity users) {
    this.store = store;
    this.devices = devices;
    this.users = users;
  }

  @GetMapping("/api/v1/alarms")
  ApiResponse<?> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest req) {
    var ids = devices.visibleDeviceIds(users.current(jwt), "anomaly.read");
    var rows =
        store.db.queryForList("SELECT * FROM alarm ORDER BY last_seen_at DESC,id DESC").stream()
            .filter(a -> ids.contains(a.get("device_id")))
            .map(this::view)
            .toList();
    return ApiResponse.ok(Views.page(rows, page, size), req.getRequestId());
  }

  @GetMapping("/api/v1/alarms/{id}")
  ApiResponse<?> detail(
      @AuthenticationPrincipal Jwt jwt, @PathVariable long id, HttpServletRequest req) {
    var rows = store.db.queryForList("SELECT * FROM alarm WHERE id=?", id);
    if (rows.isEmpty()) throw ApiProblem.missing();
    devices.requireVisibleDevices(
        users.current(jwt), "anomaly.read", List.of(rows.getFirst().get("device_id").toString()));
    var result = view(rows.getFirst());
    result.put(
        "evidence",
        store
            .db
            .queryForList(
                "SELECT evidence_type,event_id,created_at FROM alarm_evidence WHERE alarm_id=?"
                    + " ORDER BY id LIMIT 100",
                id)
            .stream()
            .map(Views::row)
            .toList());
    result.put("readOnly", true);
    return ApiResponse.ok(result, req.getRequestId());
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var result = Views.row(row);
    result.remove("parentAlarmId");
    result.remove("upstreamNodeId");
    result.remove("buildingId");
    result.remove("activeMarker");
    return result;
  }

  @RequestMapping(
      value = {"/api/v1/alarms/{id}/ack", "/api/v1/alarms/{id}/acknowledge", "/api/v1/alarm-rules"},
      method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
  void retired() {
    throw new ApiProblem(409, "LEGACY_READ_ONLY", "历史告警只读，请使用异常与故障处理功能");
  }

  @GetMapping("/api/v1/alarms/stream")
  void stream() {
    throw new ApiProblem(410, "LEGACY_FEATURE_RETIRED", "旧告警流已退出，请查询当前异常与故障");
  }
}
