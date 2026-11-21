package io.capyiot.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.capyiot.contract.DeviceEvent;
import io.capyiot.contract.DeviceMessage;
import io.capyiot.contract.DeviceSignal;
import io.capyiot.contract.Telemetry;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeviceStateTransactions {
    record Snapshot(String deviceId, String connectivity, String health, long sessionEpoch,
            long seq, Instant heartbeat, long version) {}
    record Result(Snapshot snapshot, boolean stateChanged) {}
    record Expiration(Snapshot snapshot, String campusId, String traceId, boolean changed) {}
    private record SignalContext(String upstreamNodeId, boolean enabled, long topologyVersion) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    DeviceStateTransactions(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Transactional
    public Result persist(DeviceEvent event, int partition, long offset) {
        var devices = jdbc.queryForList("SELECT campus_id,enabled,session_epoch,active_session_id FROM device WHERE id=? FOR UPDATE", event.deviceId());
        if (devices.isEmpty() || !event.campusId().equals(devices.getFirst().get("campus_id")))
            throw DeviceProblem.badRequest("事件设备或园区不存在");
        var device = devices.getFirst();
        Snapshot before = snapshot(event.deviceId());
        String fingerprint = fingerprint(event);
        var existing = jdbc.queryForList("SELECT envelope_hash FROM device_event WHERE event_id=?", String.class, event.eventId());
        if (!existing.isEmpty()) {
            if (!fingerprint.equals(existing.getFirst())) throw DeviceProblem.badRequest("eventId 与已有事件内容冲突");
            return new Result(before, false);
        }
        String status = "PROCESSED";
        if (!Boolean.TRUE.equals(device.get("enabled"))) status = "IGNORED_DISABLED";
        else if (((Number) device.get("session_epoch")).longValue() != event.sessionEpoch()
                || !event.sessionId().equals(device.get("active_session_id"))) status = "IGNORED_SESSION";
        else if (event.sessionEpoch() < before.sessionEpoch()
                || (event.sessionEpoch() == before.sessionEpoch() && event.seq() <= before.seq())) status = "IGNORED_STALE";

        jdbc.update("""
                INSERT INTO device_event(event_id,campus_id,device_id,event_type,session_epoch,seq,occurred_at,
                received_at,trace_id,processing_status,payload_json,envelope_hash,kafka_partition,kafka_offset)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, event.eventId(), event.campusId(), event.deviceId(), event.eventType().name(), event.sessionEpoch(),
                event.seq(), Timestamp.from(event.occurredAt()), Timestamp.from(event.receivedAt()), event.traceId(),
                status, serialize(event.payload()), fingerprint, partition, offset);
        jdbc.update("INSERT INTO inbox_event(consumer_group,event_id) VALUES ('device-state-group',?)", event.eventId());
        if (!status.equals("PROCESSED")) return new Result(before, false);

        String connectivity = before.connectivity();
        Instant heartbeat = before.heartbeat();
        if (event.eventType() == DeviceMessage.Type.HEARTBEAT) {
            connectivity = "ONLINE";
            if (heartbeat == null || event.receivedAt().isAfter(heartbeat)) heartbeat = event.receivedAt();
        }
        String health = before.health();
        if (event.eventType() == DeviceMessage.Type.DEVICE_FAULT
                || event.payload().path("healthStatus").asText().equals("ABNORMAL")) health = "ABNORMAL";
        if (event.eventType() == DeviceMessage.Type.DEVICE_RECOVERED) health = "NORMAL";
        boolean changed = !connectivity.equals(before.connectivity()) || !health.equals(before.health());
        long version = before.version() + 1;
        int updated = jdbc.update("""
                UPDATE device_state_snapshot SET connectivity_status=?,health_status=?,session_epoch=?,seq=?,
                last_heartbeat_at=?,version=?,last_transition_at=IF(?, ?, last_transition_at)
                WHERE device_id=? AND version=?
                """, connectivity, health, event.sessionEpoch(), event.seq(), timestamp(heartbeat), version,
                changed, Timestamp.from(event.receivedAt()), event.deviceId(), before.version());
        if (updated != 1) throw new IllegalStateException("Concurrent device state version update");
        var after = new Snapshot(event.deviceId(), connectivity, health, event.sessionEpoch(), event.seq(), heartbeat, version);
        String reason = event.eventType().name();
        if (changed) recordHistory(before, after, event.eventId(), reason, event.receivedAt());
        if (changed || event.eventType() != DeviceMessage.Type.HEARTBEAT) {
            publishSignal(event.campusId(), before, after, event.eventId(), event.traceId(), reason,
                    event.receivedAt(), event.payload(), changed);
        }
        return new Result(after, changed);
    }

    Snapshot snapshot(String deviceId) {
        return jdbc.queryForObject("SELECT * FROM device_state_snapshot WHERE device_id=?", this::readSnapshot, deviceId);
    }

    List<Snapshot> onlineSnapshots(String after, int limit) {
        return jdbc.query("""
                SELECT * FROM device_state_snapshot WHERE connectivity_status='ONLINE' AND device_id>?
                ORDER BY device_id LIMIT ?
                """, this::readSnapshot, after, limit);
    }

    @Transactional
    public Expiration expire(String deviceId, long expectedVersion, Instant now, long timeoutMillis) {
        var campus = jdbc.queryForList("SELECT campus_id FROM device WHERE id=? FOR UPDATE", String.class, deviceId);
        if (campus.isEmpty()) return null;
        Snapshot before = snapshot(deviceId);
        if (before.version() != expectedVersion || !before.connectivity().equals("ONLINE")
                || before.heartbeat() == null || before.heartbeat().plusMillis(timeoutMillis).isAfter(now)) {
            return new Expiration(before, campus.getFirst(), deviceId, false);
        }
        long version = before.version() + 1;
        int updated = jdbc.update("""
                UPDATE device_state_snapshot SET connectivity_status='OFFLINE', version=?, last_transition_at=?
                WHERE device_id=? AND version=? AND connectivity_status='ONLINE' AND last_heartbeat_at<=?
                """, version, Timestamp.from(now), deviceId, expectedVersion, Timestamp.from(now.minusMillis(timeoutMillis)));
        if (updated != 1) throw new IllegalStateException("Offline transition lost its expected version");
        var after = new Snapshot(deviceId, "OFFLINE", before.health(), before.sessionEpoch(),
                before.seq(), before.heartbeat(), version);
        String id = UUID.randomUUID().toString();
        recordHistory(before, after, id, "HEARTBEAT_TIMEOUT", now);
        JsonNode evidence = json.createObjectNode().put("lastHeartbeatAt", before.heartbeat().toString())
                .put("timeoutMs", timeoutMillis);
        publishSignal(campus.getFirst(), before, after, id, id, "HEARTBEAT_TIMEOUT", now, evidence, true);
        return new Expiration(after, campus.getFirst(), id, true);
    }

    private Snapshot readSnapshot(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Snapshot(rs.getString("device_id"), rs.getString("connectivity_status"),
                rs.getString("health_status"), rs.getLong("session_epoch"), rs.getLong("seq"),
                DeviceQueries.instant(rs, "last_heartbeat_at"), rs.getLong("version"));
    }

    private void recordHistory(Snapshot before, Snapshot after, String cause, String reason, Instant at) {
        jdbc.update("""
                INSERT INTO device_state_history(device_id,event_id,old_connectivity,new_connectivity,
                old_health,new_health,reason,state_version,created_at) VALUES (?,?,?,?,?,?,?,?,?)
                """, after.deviceId(), cause, before.connectivity(), after.connectivity(),
                before.health(), after.health(), reason, after.version(), Timestamp.from(at));
    }

    private void publishSignal(String campusId, Snapshot before, Snapshot after, String cause,
            String traceId, String reason, Instant occurredAt, JsonNode evidence, boolean changed) {
        SignalContext context = jdbc.queryForObject("""
                SELECT n.upstream_device_id,d.enabled,c.topology_version
                FROM device d JOIN campus c ON d.campus_id=c.id
                LEFT JOIN network_link n ON n.downstream_device_id=d.id WHERE d.id=?
                """, (rs, row) -> new SignalContext(rs.getString(1), rs.getBoolean(2), rs.getLong(3)), after.deviceId());
        var payload = new DeviceSignal.State(after.connectivity(), after.health(), after.version(), cause,
                before.connectivity(), before.health(), reason, evidence, context.upstreamNodeId(),
                context.enabled(), context.topologyVersion());
        String id = UUID.randomUUID().toString();
        String type = changed ? "DEVICE_STATE_CHANGED" : "DEVICE_DIAGNOSTIC";
        var event = new DeviceSignal(id, type, 1, campusId, after.deviceId(), occurredAt, Instant.now(),
                traceId, "device-service", payload);
        jdbc.update("""
                INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,event_id,topic,message_key,payload_json,trace_parent)
                VALUES ('DEVICE',?,?,?,?,?,?,?)
                """, after.deviceId(), type, id, DeviceEvent.STATUS_TOPIC, after.deviceId(), serialize(event), Telemetry.traceParent());
    }

    private String fingerprint(DeviceEvent event) {
        try {
            byte[] bytes = json.writeValueAsBytes(List.of(event.eventId(), event.eventType(), event.schemaVersion(),
                    event.campusId(), event.deviceId(), event.sessionId(), event.sessionEpoch(), event.seq(),
                    event.occurredAt(), json.convertValue(event.payload(), Object.class)));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException | JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("Invalid event JSON", error); }
    }

    private Timestamp timestamp(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
}
