package io.capyiot.workflow;

import static io.capyiot.workflow.WorkOrderModels.*;

import io.capyiot.contract.ApiResponse;
import io.capyiot.outbox.OutboxRelay;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/workorders")
@SecurityRequirement(name = "bearerAuth")
class WorkOrderController {
    private final WorkOrderStore store;
    private final WorkOrderService orders;
    private final AlarmSourceClient alarms;
    private final WorkOrderNotifications notifications;
    private final WorkflowDeadLetters deadLetters;
    private final OutboxRelay relay;

    WorkOrderController(WorkOrderStore store, WorkOrderService orders, AlarmSourceClient alarms,
            WorkOrderNotifications notifications, WorkflowDeadLetters deadLetters, OutboxRelay relay) {
        this.store = store;
        this.orders = orders;
        this.alarms = alarms;
        this.notifications = notifications;
        this.deadLetters = deadLetters;
        this.relay = relay;
    }

    @GetMapping
    ApiResponse<Page<Order>> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Status status,
            @RequestParam(required = false) Priority priority, @RequestParam(required = false) String assignee,
            @RequestParam(defaultValue = "false") boolean mine, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Actor actor = actor(jwt);
        return ok(store.list(actor.campus(), status, priority, mine ? actor.username() : assignee, page, size));
    }

    @GetMapping("/summary")
    ApiResponse<Summary> summary(@AuthenticationPrincipal Jwt jwt) {
        return ok(store.summary(actor(jwt).campus()));
    }

    @GetMapping("/by-alarm/{alarmId}")
    ApiResponse<Order> byAlarm(@AuthenticationPrincipal Jwt jwt, @PathVariable long alarmId) {
        return ok(store.bySource(actor(jwt).campus(), alarmId));
    }

    @GetMapping("/{id}")
    ApiResponse<Detail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @RequestParam(defaultValue = "1") int timelinePage, @RequestParam(defaultValue = "30") int size) {
        return ok(orders.detail(actor(jwt), id, timelinePage, size));
    }

    @PostMapping
    ApiResponse<Created> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Create request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw WorkOrderProblem.invalid("请求键仅接受 1–128 个字母、数字及 . _ : -");
        }
        Actor actor = actor(jwt);
        var existing = store.bySource(actor.campus(), request.sourceAlarmId());
        var observed = existing == null ? alarms.get(request.sourceAlarmId(), actor.campus(), jwt.getTokenValue()) : null;
        return ok(orders.create(actor, request, idempotencyKey, observed));
    }

    @PostMapping("/{id}/transitions")
    ApiResponse<Order> transition(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @Valid @RequestBody Transition request) {
        Actor actor = actor(jwt);
        var current = store.require(actor.campus(), id);
        WorkOrderActions.check(actor, current, request);
        var observed = WorkOrderActions.needsAlarm(request.action())
                ? alarms.get(current.sourceAlarmId(), actor.campus(), jwt.getTokenValue()) : null;
        return ok(orders.transition(actor, id, request, observed));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@AuthenticationPrincipal Jwt jwt) throws IOException {
        return notifications.open(actor(jwt).campus(), jwt.getExpiresAt());
    }

    @GetMapping("/dead-letters")
    ApiResponse<List<WorkflowDeadLetters.Dead>> dead(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") long after) {
        return ok(deadLetters.list(actor(jwt).campus(), after));
    }

    @PostMapping("/dead-letters/{id}/retry")
    ApiResponse<Boolean> replay(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        Actor actor = actor(jwt);
        deadLetters.retry(actor.campus(), id, actor.username());
        return ok(true);
    }

    @GetMapping("/outbox/dead")
    ApiResponse<List<OutboxRelay.Dead>> outbox(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") long after) {
        return ok(relay.dead(actor(jwt).campus(), after, 50));
    }

    @PostMapping("/outbox/{id}/retry")
    ApiResponse<Boolean> retry(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        Actor actor = actor(jwt);
        if (!relay.retry(actor.campus(), id, actor.username())) throw WorkOrderProblem.missing("待补发事件不存在");
        return ok(true);
    }

    private static Actor actor(Jwt jwt) {
        String campus = jwt.getClaimAsString("campusId");
        String username = jwt.getSubject();
        if (campus == null || campus.isBlank() || username == null || username.isBlank()) {
            throw WorkOrderProblem.forbidden("账号缺少园区授权");
        }
        return new Actor(campus, username, jwt.getClaimAsStringList("roles").contains("ADMIN"));
    }

    private static <T> ApiResponse<T> ok(T data) { return ApiResponse.ok(data, UUID.randomUUID().toString()); }

    @ExceptionHandler(WorkOrderProblem.class)
    ResponseEntity<?> error(WorkOrderProblem problem) {
        return ResponseEntity.status(problem.status).body(Map.of("code", problem.code, "message", problem.getMessage(),
                "details", problem.details, "traceId", UUID.randomUUID().toString()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception failure) { return error(WorkOrderProblem.invalid("请求字段格式不正确")); }
}
