package io.capyiot.alarm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.ApiResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AlarmQueries {
    record Alarm(long id, String alarmNo, String deviceId, String deviceName, String buildingId,
            String alarmType, String severity, String status, String relationKind, Long parentAlarmId,
            String rootNetworkNodeId, int suppressedCount, long childCount, long affectedCount,
            Instant firstSeenAt, Instant lastSeenAt, Instant recoveredAt, Instant acknowledgedAt,
            String acknowledgedBy, long version) {}
    record Summary(long active, long critical, long grouped, long affected) {}
    record Page<T>(List<T> items, long total, int page, int size) {}
    record Detail(Alarm alarm, Page<Alarm> children, Page<Evidence> evidence, Page<Impact> devices) {}
    record Evidence(String eventId, String causeEventId, String deviceId, String reason,
            String evidenceType, String upstreamNodeId, long topologyVersion, String traceId, Instant occurredAt,
            Instant receivedAt, JsonNode payload) {}
    record Impact(String deviceId, String deviceName, String impactKind, Instant firstObservedAt) {}
    record ImpactMarker(String deviceId, Long alarmId, String relationKind, String rootNetworkNodeId) {}
    record Rules(long topologyWindowSeconds, int minimumDownstream, long flapCooldownSeconds,
            double temperatureLimit, double temperatureRecovery) {}

    private static final String SELECT = """
            SELECT a.*,
                   (SELECT COUNT(*) FROM alarm child WHERE child.parent_alarm_id=a.id) child_count,
                   (SELECT COUNT(*) FROM alarm_device_link l WHERE l.alarm_id=a.id AND l.impact_kind='AFFECTED') affected_count
            FROM alarm a
            """;

    private static final RowMapper<Alarm> VIEW = (rs, row) -> {
        long id = rs.getLong("id");
        return new Alarm(id, "ALM-%06d".formatted(id), rs.getString("device_id"), rs.getString("device_name"),
                rs.getString("building_id"), rs.getString("alarm_type"), rs.getString("severity"),
                rs.getString("status"), rs.getString("relation_kind"), rs.getObject("parent_alarm_id", Long.class),
                "NETWORK_INCIDENT".equals(rs.getString("alarm_type")) ? rs.getString("device_id") : null,
                rs.getInt("suppressed_count"), rs.getLong("child_count"), rs.getLong("affected_count"),
                time(rs, "first_seen_at"), time(rs, "last_seen_at"), time(rs, "recovered_at"),
                time(rs, "acknowledged_at"), rs.getString("acknowledged_by"), rs.getLong("version"));
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AlarmRules rules;
    private final TopologyProjection topology;
    private final AlarmOutbox outbox;

    AlarmQueries(JdbcTemplate jdbc, ObjectMapper json, AlarmRules rules, TopologyProjection topology,
            AlarmOutbox outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.rules = rules;
        this.topology = topology;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public Page<Alarm> list(String campus, String severity, String status, String building, String type,
            boolean mainOnly, int page, int size) {
        checkPage(page, size);
        var args = new ArrayList<Object>();
        args.add(campus);
        var where = new StringBuilder(" WHERE a.campus_id=?");
        if (mainOnly) where.append(" AND a.parent_alarm_id IS NULL");
        filter(severity, "a.severity", List.of("CRITICAL", "WARNING"), where, args);
        filter(status, "a.status", List.of("OPEN", "ACKNOWLEDGED", "RECOVERED"), where, args);
        filter(type, "a.alarm_type", List.of("DEVICE_OFFLINE", "DEVICE_FAULT", "NETWORK_UPSTREAM_FAILURE",
                "SENSOR_THRESHOLD_BREACH", "NETWORK_INCIDENT"), where, args);
        filter(building, "a.building_id", List.of(), where, args);
        return page(SELECT, "SELECT COUNT(*) FROM alarm a", where.toString(),
                " ORDER BY a.active_marker DESC,a.last_seen_at DESC,a.id DESC", VIEW, page, size, args.toArray());
    }

    Summary summary(String campus) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) active,COALESCE(SUM(severity='CRITICAL'),0) critical,
                    COALESCE(SUM(alarm_type='NETWORK_INCIDENT'),0) grouped,
                    (SELECT COUNT(DISTINCT child.device_id) FROM alarm child JOIN alarm root ON root.id=child.parent_alarm_id
                     WHERE root.campus_id=? AND root.active_marker=1 AND child.active_marker=1
                      AND child.alarm_type='DEVICE_OFFLINE') affected
                FROM alarm WHERE campus_id=? AND parent_alarm_id IS NULL AND active_marker=1
                """, (rs, row) -> new Summary(rs.getLong("active"), rs.getLong("critical"),
                        rs.getLong("grouped"), rs.getLong("affected")), campus, campus);
    }

    List<ImpactMarker> markers(String campus, List<String> devices) {
        if (devices.isEmpty()) return List.of();
        if (devices.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最多查询 100 个节点");
        var parameters = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        return parameters.query("""
                SELECT a.device_id,COALESCE(p.id,a.id) alarm_id,COALESCE(p.relation_kind,a.relation_kind) relation_kind,
                       CASE WHEN p.alarm_type='NETWORK_INCIDENT' THEN p.device_id
                            WHEN a.alarm_type='NETWORK_INCIDENT' THEN a.device_id ELSE NULL END root_node
                FROM alarm a LEFT JOIN alarm p ON p.id=a.parent_alarm_id
                WHERE a.campus_id=:campus AND a.active_marker=1 AND a.device_id IN (:devices)
                ORDER BY (a.alarm_type='NETWORK_INCIDENT') DESC,(p.id IS NOT NULL) DESC,a.id DESC
                """, java.util.Map.of("campus", campus, "devices", devices), (rs, row) ->
                new ImpactMarker(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)));
    }

    List<Alarm> forEvent(String campus, String eventId) {
        return jdbc.query(SELECT + """
                WHERE a.campus_id=? AND a.id IN (
                    SELECT COALESCE(child.parent_alarm_id,child.id)
                    FROM raw_event r JOIN alarm_evidence e ON e.event_id=r.event_id
                    JOIN alarm child ON child.id=e.alarm_id
                    WHERE r.campus_id=? AND r.cause_event_id=?
                ) ORDER BY a.last_seen_at DESC,a.id DESC LIMIT 10
                """, VIEW, campus, campus, eventId);
    }

    List<Alarm> forDevice(String campus, String deviceId) {
        return jdbc.query(SELECT + """
                WHERE a.campus_id=? AND a.parent_alarm_id IS NULL AND
                    (a.device_id=? OR EXISTS (SELECT 1 FROM alarm_device_link l WHERE l.alarm_id=a.id AND l.device_id=?))
                ORDER BY a.active_marker DESC,a.last_seen_at DESC,a.id DESC LIMIT 8
                """, VIEW, campus, deviceId, deviceId);
    }

    @Transactional(readOnly = true)
    public Detail detail(String campus, long id, int childPage, int evidencePage, int devicePage, int size) {
        Alarm head = find(campus, id);
        var children = page(SELECT, "SELECT COUNT(*) FROM alarm a", " WHERE a.parent_alarm_id=? AND a.campus_id=?",
                " ORDER BY a.id", VIEW, childPage, size, id, campus);
        String from = " FROM alarm_evidence e JOIN raw_event r ON r.event_id=e.event_id JOIN alarm a ON a.id=e.alarm_id";
        var evidence = page("SELECT e.evidence_type,r.*" + from, "SELECT COUNT(*)" + from,
                " WHERE (a.id=? OR a.parent_alarm_id=?) AND a.campus_id=?", " ORDER BY e.created_at DESC,e.id DESC",
                this::evidence, evidencePage, size, id, id, campus);
        var devices = page("SELECT * FROM alarm_device_link", "SELECT COUNT(*) FROM alarm_device_link",
                " WHERE alarm_id=?", " ORDER BY impact_kind DESC,device_id", (rs, row) ->
                        new Impact(rs.getString("device_id"), rs.getString("device_name"), rs.getString("impact_kind"),
                                time(rs, "first_observed_at")), devicePage, size, id);
        return new Detail(head, children, evidence, devices);
    }

    @Transactional
    public Alarm acknowledge(String campus, long id, String actor) {
        topology.lock(campus);
        Alarm alarm = find(campus, id);
        if (alarm.parentAlarmId() != null || "RECOVERED".equals(alarm.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅可确认活跃主告警");
        }
        if ("OPEN".equals(alarm.status())) {
            jdbc.update("""
                    UPDATE alarm SET status='ACKNOWLEDGED',acknowledged_at=CURRENT_TIMESTAMP(3),acknowledged_by=?,
                        version=version+1 WHERE id=?
                    """, actor, id);
            rules.incrementRevision(campus);
            outbox.publish(campus, java.util.Set.of(id), Instant.now(), UUID.randomUUID().toString());
        }
        return find(campus, id);
    }

    long revision(String campus) {
        return jdbc.queryForList("SELECT alarm_revision FROM topology_checkpoint WHERE campus_id=?", Long.class, campus)
                .stream().findFirst().orElse(0L);
    }

    private Alarm find(String campus, long id) {
        return jdbc.query(SELECT + " WHERE a.id=? AND a.campus_id=?", VIEW, id, campus).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "告警不存在"));
    }

    private <T> Page<T> page(String select, String count, String where, String order,
            RowMapper<T> mapper, int page, int size, Object... filters) {
        checkPage(page, size);
        long total = jdbc.queryForObject(count + where, Long.class, filters);
        var args = new ArrayList<>(List.of(filters));
        args.add(size);
        args.add((page - 1L) * size);
        var items = jdbc.query(select + where + order + " LIMIT ? OFFSET ?", mapper, args.toArray());
        return new Page<>(items, total, page, size);
    }

    private static void checkPage(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数无效");
        }
    }

    private static void filter(String value, String field, List<String> allowed,
            StringBuilder where, List<Object> args) {
        if (value == null || value.isBlank()) return;
        if (!allowed.isEmpty() && !allowed.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "筛选条件无效");
        }
        where.append(" AND ").append(field).append("=?");
        args.add(value);
    }

    private Evidence evidence(ResultSet rs, int row) throws SQLException {
        JsonNode payload;
        try {
            payload = json.readTree(rs.getString("payload_json"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new SQLException("Invalid stored evidence JSON", error);
        }
        return new Evidence(rs.getString("event_id"), rs.getString("cause_event_id"), rs.getString("device_id"),
                rs.getString("event_type"), rs.getString("evidence_type"), rs.getString("upstream_node_id"),
                rs.getLong("topology_version"), rs.getString("trace_id"), time(rs, "occurred_at"),
                time(rs, "received_at"), payload);
    }

    static <T> ApiResponse<T> response(T payload) { return ApiResponse.ok(payload, UUID.randomUUID().toString()); }

    private static Instant time(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field);
        return value != null ? value.toInstant() : null;
    }
}
