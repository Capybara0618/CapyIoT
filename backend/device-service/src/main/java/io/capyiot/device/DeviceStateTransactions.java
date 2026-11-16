package io.capyiot.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.capyiot.contract.DeviceEvent;
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
        if (event.eventType() == io.capyiot.contract.DeviceMessage.Type.HEARTBEAT) {
            connectivity = "ONLINE";
            if (heartbeat == null || event.receivedAt().isAfter(heartbeat)) heartbeat = event.receivedAt();
        }
        String health = before.health();
        if (event.eventType() == io.capyiot.contract.DeviceMessage.Type.DEVICE_FAULT
                || event.payload().path("healthStatus").asText().equals("ABNORMAL")) health = "ABNORMAL";
        if (event.eventType() == io.capyiot.contract.DeviceMessage.Type.DEVICE_RECOVERED) health = "NORMAL";
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
        if (changed) {
            jdbc.update("""
                    INSERT INTO device_state_history(device_id,event_id,old_connectivity,new_connectivity,
                    old_health,new_health,reason,state_version,created_at) VALUES (?,?,?,?,?,?,?,?,?)
                    """, event.deviceId(), event.eventId(), before.connectivity(), connectivity,
                    before.health(), health, event.eventType().name(), version, Timestamp.from(event.receivedAt()));
            String id = UUID.randomUUID().toString();
            var payload = Map.of("connectivityStatus", connectivity, "healthStatus", health,
                    "stateVersion", version, "causationId", event.eventId());
            String body = serialize(Map.of("eventId", id, "eventType", "DEVICE_STATE_CHANGED", "schemaVersion", 1,
                    "campusId", event.campusId(), "deviceId", event.deviceId(), "occurredAt", event.receivedAt(),
                    "receivedAt", Instant.now(), "traceId", event.traceId(), "producer", "device-service", "payload", payload));
            jdbc.update("""
                    INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,event_id,topic,message_key,payload_json)
                    VALUES ('DEVICE',?,'DEVICE_STATE_CHANGED',?,?,?,?)
                    """, event.deviceId(), id, DeviceEvent.STATUS_TOPIC, event.deviceId(), body);
        }
        return new Result(after, changed);
    }

    Snapshot snapshot(String deviceId) {
        return jdbc.queryForObject("SELECT * FROM device_state_snapshot WHERE device_id=?", (rs, row) ->
                new Snapshot(deviceId, rs.getString("connectivity_status"), rs.getString("health_status"),
                        rs.getLong("session_epoch"), rs.getLong("seq"), DeviceQueries.instant(rs, "last_heartbeat_at"),
                        rs.getLong("version")), deviceId);
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
