package io.capyiot.alarm;

import static io.capyiot.alarm.AlarmQueries.response;

import io.capyiot.contract.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
class AlarmController {
    private final AlarmQueries queries;
    private final AlarmNotifications notifications;
    private final AlarmQueries.Rules rules;

    AlarmController(AlarmQueries queries, AlarmNotifications notifications,
            @Value("${capyiot.alarm-topology-window-seconds:60}") long window,
            @Value("${capyiot.alarm-flap-cooldown-seconds:60}") long cooldown,
            @Value("${capyiot.alarm-minimum-downstream:2}") int minimum,
            @Value("${capyiot.alarm-temperature-limit:35}") double limit,
            @Value("${capyiot.alarm-temperature-recovery:33}") double recovery) {
        this.queries = queries;
        this.notifications = notifications;
        this.rules = new AlarmQueries.Rules(window, minimum, cooldown, limit, recovery);
    }

    @GetMapping("/alarms")
    ApiResponse<AlarmQueries.Page<AlarmQueries.Alarm>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String severity, @RequestParam(required = false) String status,
            @RequestParam(required = false) String buildingId, @RequestParam(required = false) String alarmType,
            @RequestParam(defaultValue = "true") boolean mainOnly, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return response(queries.list(campus(jwt), severity, status, buildingId, alarmType, mainOnly, page, size));
    }

    @GetMapping("/alarms/summary")
    ApiResponse<AlarmQueries.Summary> summary(@AuthenticationPrincipal Jwt jwt) {
        return response(queries.summary(campus(jwt)));
    }

    @GetMapping("/alarms/impact")
    ApiResponse<List<AlarmQueries.ImpactMarker>> impacts(@AuthenticationPrincipal Jwt jwt,
            @RequestParam List<String> deviceIds) {
        return response(queries.markers(campus(jwt), deviceIds));
    }

    @GetMapping("/alarms/{id}")
    ApiResponse<AlarmQueries.Detail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @RequestParam(defaultValue = "1") int childPage, @RequestParam(defaultValue = "1") int evidencePage,
            @RequestParam(defaultValue = "1") int devicePage, @RequestParam(defaultValue = "20") int size) {
        return response(queries.detail(campus(jwt), id, childPage, evidencePage, devicePage, size));
    }

    @PostMapping("/alarms/{id}/acknowledge")
    ApiResponse<AlarmQueries.Alarm> acknowledge(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return response(queries.acknowledge(campus(jwt), id, jwt.getSubject()));
    }

    @GetMapping("/alarm-rules")
    ApiResponse<AlarmQueries.Rules> rules() { return response(rules); }

    @GetMapping(value = "/alarms/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@AuthenticationPrincipal Jwt jwt) throws IOException {
        return notifications.open(campus(jwt), jwt.getExpiresAt());
    }

    private static String campus(Jwt jwt) {
        String campus = jwt.getClaimAsString("campusId");
        if (campus == null || campus.isBlank()) throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "账号缺少园区授权");
        return campus;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> error(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("code", "ALARM_REQUEST_FAILED",
                "message", error.getReason() == null ? "告警请求失败" : error.getReason(), "traceId", UUID.randomUUID().toString()));
    }
}
