package io.capyiot.workflow;

import static io.capyiot.workflow.WorkOrderModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.AlarmEvent;
import io.capyiot.outbox.OutboxRelay;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkOrderService {
    static final String TOPIC = "iot.workorder.events.v1";
    static final String GROUP = "workflow-creator-group";
    private record RequestEntry(String fingerprint, long order) {}

    private final JdbcTemplate jdbc;
    private final WorkOrderStore store;
    private final OutboxRelay outbox;
    private final ObjectMapper json;

    WorkOrderService(JdbcTemplate jdbc, WorkOrderStore store, OutboxRelay outbox, ObjectMapper json) {
        this.jdbc = jdbc;
        this.store = store;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public void consume(AlarmEvent event) {
        store.lockCampus(event.campusId());
        int inserted = jdbc.update("INSERT IGNORE INTO inbox_event(consumer_group,event_id) VALUES (?,?)", GROUP, event.eventId());
        if (inserted == 0) return;
        boolean newer = store.observe(event.payload());
        var current = store.source(event.campusId(), event.alarmId());
        var existing = store.bySource(event.campusId(), event.alarmId());
        if (existing == null && current.automatic()) {
            insert(current, null, null, null, "system", event.traceId());
        } else if (existing != null && newer) {
            store.changed(event.campusId());
        }
    }

    @Transactional
    public Created create(Actor actor, Create request, String key, AlarmEvent.Source observed) {
        String campus = actor.campus();
        store.lockCampus(campus);
        String fingerprint = key == null ? null : fingerprint(request);
        if (key != null) {
            var previous = jdbc.query("""
                    SELECT fingerprint,order_id FROM work_order_request WHERE campus_id=? AND idempotency_key=?
                    """, (rs, row) -> new RequestEntry(rs.getString(1), rs.getLong(2)), campus, key)
                    .stream().findFirst().orElse(null);
            if (previous != null) {
                if (!fingerprint.equals(previous.fingerprint())) {
                    throw WorkOrderProblem.conflict("IDEMPOTENCY_KEY_REUSED", "同一请求键不能用于不同的开单内容");
                }
                return new Created(store.require(campus, previous.order()), false);
            }
        }
        var existing = store.bySource(campus, request.sourceAlarmId());
        boolean created = existing == null;
        if (created) {
            if (observed != null) store.observe(observed);
            var latest = store.source(campus, request.sourceAlarmId());
            if (latest == null) throw WorkOrderProblem.missing("来源告警不存在");
            if (latest.parentAlarmId() != null) {
                throw WorkOrderProblem.conflict("WORKORDER_SOURCE_IS_CHILD", "请从主告警创建工单");
            }
            String assignee = request.assignee() == null || request.assignee().isBlank()
                    ? null : WorkOrderActions.assignee(actor, request.assignee());
            existing = insert(latest, request.title(), request.priority(), assignee, actor.username(),
                    UUID.randomUUID().toString());
        }
        if (key != null) jdbc.update("""
                INSERT INTO work_order_request(campus_id,idempotency_key,fingerprint,order_id) VALUES (?,?,?,?)
                """, campus, key, fingerprint, existing.id());
        return new Created(existing, created);
    }

    @Transactional
    public Order transition(Actor actor, long id, Transition request, AlarmEvent.Source observed) {
        String campus = actor.campus();
        store.lockCampus(campus);
        var order = store.require(campus, id);
        WorkOrderActions.check(actor, order, request);
        Action action = request.action();
        if (WorkOrderActions.needsAlarm(action)) {
            if (observed == null || observed.id() != order.sourceAlarmId() || !campus.equals(observed.campusId())) {
                throw new IllegalStateException("Authoritative alarm snapshot required");
            }
            store.observe(observed);
            order = store.require(campus, id);
        }
        var source = order.source();
        Status next = WorkOrderActions.next(order.status(), action);
        String assignee = order.assignee();
        Long verified = order.verifiedAlarmVersion();
        Instant verifiedAt = order.verifiedAt();
        Instant closedAt = order.closedAt();
        Instant now = Instant.now();
        switch (action) {
            case ASSIGN, REASSIGN -> {
                assignee = WorkOrderActions.assignee(actor, request.assignee());
                if (action == Action.REASSIGN && assignee.equals(order.assignee())) {
                    throw WorkOrderProblem.conflict("WORKORDER_SAME_ASSIGNEE", "请选择其他处理人");
                }
            }
            case VERIFY_RECOVERY -> {
                if (!source.recovered()) throw WorkOrderProblem.conflict("ALARM_NOT_RECOVERED", "来源告警尚未恢复，暂不能通过验证");
                if (order.recoveryVerified()) throw WorkOrderProblem.conflict("RECOVERY_ALREADY_VERIFIED", "当前恢复已验证");
                verified = source.version();
                verifiedAt = now;
            }
            case CLOSE -> {
                if (!order.recoveryVerified()) {
                    throw WorkOrderProblem.conflict("WORKORDER_RECOVERY_REQUIRED", "关闭前需完成当前告警的恢复验证");
                }
                closedAt = now;
            }
            case RETURN_TO_PROCESSING, REOPEN -> {
                verified = null;
                verifiedAt = null;
                if (action == Action.REOPEN) {
                    if (source.recovered() || source.parentAlarmId() != null) {
                        throw WorkOrderProblem.conflict("WORKORDER_REOPEN_NOT_ALLOWED", "仅可重开来源重新活跃的主告警工单");
                    }
                    assignee = null;
                    closedAt = null;
                }
            }
            default -> { /* The action only changes status or adds an audit entry. */ }
        }
        long version = order.version() + 1;
        int changed = jdbc.update("""
                UPDATE work_order SET status=?,assignee=?,verified_alarm_version=?,verified_at=?,closed_at=?,version=?,
                    updated_at=CURRENT_TIMESTAMP(3) WHERE campus_id=? AND id=? AND version=?
                """, next.name(), assignee, verified, timestamp(verifiedAt), timestamp(closedAt), version,
                campus, id, order.version());
        if (changed != 1) throw WorkOrderProblem.conflict("WORKORDER_VERSION_CONFLICT", "工单已更新，请刷新后重试");
        String remark = request.remark() == null ? null : request.remark().strip();
        if (action == Action.ADD_NOTE) {
            jdbc.update("INSERT INTO work_order_comment(order_id,order_version,actor,content) VALUES (?,?,?,?)",
                    id, version, actor.username(), remark);
        } else {
            history(id, version, action.name(), order.status(), next, actor.username(), assignee, remark);
        }
        Order updated = store.require(campus, id);
        publish(campus, updated, UUID.randomUUID().toString());
        return updated;
    }

    @Transactional(readOnly = true)
    public Detail detail(Actor actor, long id, int page, int size) {
        Order order = store.require(actor.campus(), id);
        return new Detail(order, store.timeline(id, page, size), WorkOrderActions.available(actor, order), order.recoveryVerified());
    }

    private Order insert(AlarmEvent.Source source, String title, Priority priority, String assignee,
            String actor, String trace) {
        String campus = source.campusId();
        String cleanTitle = title == null || title.isBlank() ? source.deviceName() + " · " + source.alarmNo() + " 处置" : title.strip();
        Priority selected = priority == null ? ("CRITICAL".equals(source.severity()) ? Priority.HIGH : Priority.MEDIUM) : priority;
        jdbc.update("""
                INSERT INTO work_order(campus_id,source_alarm_id,source_business_key,source_alarm_no,title,priority,status,
                    assignee,created_by,version) VALUES (?,?,?,?,?,?,?,?,?,?)
                """, campus, source.id(), campus + ":" + source.id(), source.alarmNo(), cleanTitle, selected.name(),
                assignee == null ? "OPEN" : "ASSIGNED", assignee, actor, assignee == null ? 0 : 1);
        Order order = store.bySource(campus, source.id());
        history(order.id(), 0, "CREATE", null, Status.OPEN, actor, null, null);
        if (assignee != null) history(order.id(), 1, "ASSIGN", Status.OPEN, Status.ASSIGNED, actor, assignee, null);
        publish(campus, order, trace);
        return order;
    }

    private void history(long id, long version, String action, Status from, Status to, String actor,
            String assignee, String remark) {
        jdbc.update("""
                INSERT INTO work_order_history(order_id,order_version,action,from_status,to_status,actor,assignee,remark)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, version, action, from == null ? null : from.name(), to.name(), actor, assignee, remark);
    }

    private void publish(String campus, Order order, String trace) {
        String eventId = UUID.randomUUID().toString();
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "WORKORDER_CHANGED");
        envelope.put("schemaVersion", 1);
        envelope.put("campusId", campus);
        envelope.put("workOrderId", order.id());
        envelope.put("occurredAt", Instant.now());
        envelope.put("receivedAt", Instant.now());
        envelope.put("traceId", trace);
        envelope.put("producer", "workflow-service");
        envelope.put("payload", java.util.Map.of("status", order.status(), "version", order.version(),
                "sourceAlarmId", order.sourceAlarmId()));
        outbox.enqueue(campus, "WORKORDER", Long.toString(order.id()), "WORKORDER_CHANGED", eventId, TOPIC, envelope);
        store.changed(campus);
    }

    private String fingerprint(Create request) {
        try {
            byte[] canonical = json.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint workorder request", failure);
        }
    }

    private static Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
}
