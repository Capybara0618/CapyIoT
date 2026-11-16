package io.capyiot.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.TopologyEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class DeviceTopology {
    private static final String NODE_SQL = """
            SELECT d.id,d.name,d.device_code,d.type,d.building_id,b.name building_name,
                   n.upstream_device_id,d.enabled
            FROM device d JOIN building b ON b.id=d.building_id
            LEFT JOIN network_link n ON n.downstream_device_id=d.id
            WHERE d.campus_id=?
            """;
    private static final RowMapper<TopologyEvent.Node> NODE = (rs, row) -> new TopologyEvent.Node(
            rs.getString("id"), rs.getString("name"), rs.getString("device_code"), rs.getString("type"),
            rs.getString("building_id"), rs.getString("building_name"), rs.getString("upstream_device_id"),
            rs.getBoolean("enabled"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DeviceNotifications notifications;

    DeviceTopology(JdbcTemplate jdbc, ObjectMapper json, DeviceNotifications notifications) {
        this.jdbc = jdbc;
        this.json = json;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public TopologyEvent.Page snapshot(String campusId, String after, Long expectedVersion) {
        var versions = jdbc.queryForList("SELECT topology_version FROM campus WHERE id=?", Long.class, campusId);
        if (versions.isEmpty()) throw DeviceProblem.notFound("园区不存在");
        long version = versions.getFirst();
        if (expectedVersion != null && version != expectedVersion) {
            throw DeviceProblem.conflict("拓扑已更新，请重新获取快照");
        }
        var nodes = jdbc.query(NODE_SQL + " AND d.id>? ORDER BY d.id LIMIT 101", NODE, campusId, after);
        boolean more = nodes.size() > 100;
        List<TopologyEvent.Node> page = more ? nodes.subList(0, 100) : nodes;
        return new TopologyEvent.Page(version, page, more ? page.getLast().id() : null);
    }

    long publishChange(String campusId, String deviceId) {
        jdbc.update("UPDATE campus SET topology_version=topology_version+1 WHERE id=?", campusId);
        long version = jdbc.queryForObject("SELECT topology_version FROM campus WHERE id=?", Long.class, campusId);
        var node = jdbc.queryForObject(NODE_SQL + " AND d.id=?", NODE, campusId, deviceId);
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        var event = new TopologyEvent(id, "TOPOLOGY_NODE_CHANGED", 1, campusId, now, now, id,
                "device-service", new TopologyEvent.Change(version, node));
        String body;
        try {
            body = json.writeValueAsString(event);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize topology event", error);
        }
        jdbc.update("""
                INSERT INTO outbox_event(aggregate_type,aggregate_id,event_type,event_id,topic,message_key,payload_json)
                VALUES ('TOPOLOGY',?,'TOPOLOGY_NODE_CHANGED',?,?,?,?)
                """, deviceId, id, TopologyEvent.TOPIC, campusId, body);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { notifications.changed(campusId, deviceId, id, true); }
        });
        return version;
    }
}
