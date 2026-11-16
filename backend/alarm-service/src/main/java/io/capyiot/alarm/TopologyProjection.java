package io.capyiot.alarm;

import io.capyiot.contract.TopologyEvent;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class TopologyProjection {
    private final JdbcTemplate jdbc;

    TopologyProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long version(String campus) {
        return jdbc.queryForList("SELECT version FROM topology_checkpoint WHERE campus_id=?", Long.class, campus)
                .stream().findFirst().orElse(-1L);
    }

    long lock(String campus) {
        jdbc.update("INSERT IGNORE INTO topology_checkpoint(campus_id,version) VALUES (?,-1)", campus);
        return jdbc.queryForObject("SELECT version FROM topology_checkpoint WHERE campus_id=? FOR UPDATE", Long.class, campus);
    }

    TopologyEvent.Node node(String campus, String id) {
        return jdbc.query("SELECT * FROM topology_projection WHERE campus_id=? AND device_id=?", (rs, row) ->
                new TopologyEvent.Node(rs.getString("device_id"), rs.getString("device_name"), rs.getString("device_code"),
                        rs.getString("device_type"), rs.getString("building_id"), rs.getString("building_name"),
                        rs.getString("upstream_node_id"), rs.getBoolean("enabled")), campus, id)
                .stream().findFirst().orElse(null);
    }

    @Transactional
    public void replace(String campus, long version, List<TopologyEvent.Node> nodes) {
        if (lock(campus) >= version) return;
        jdbc.update("DELETE FROM topology_projection WHERE campus_id=?", campus);
        for (var node : nodes) save(campus, node);
        jdbc.update("UPDATE topology_checkpoint SET version=? WHERE campus_id=?", version, campus);
    }

    @Transactional
    public boolean apply(TopologyEvent event) {
        long current = lock(event.campusId());
        long version = event.payload().version();
        if (current >= version) return true;
        if (current < 0 || current + 1 != version) return false;
        save(event.campusId(), event.payload().node());
        jdbc.update("UPDATE topology_checkpoint SET version=? WHERE campus_id=?", version, event.campusId());
        return true;
    }

    private void save(String campus, TopologyEvent.Node node) {
        jdbc.update("""
                INSERT INTO topology_projection(campus_id,device_id,device_name,device_code,device_type,building_id,
                    building_name,upstream_node_id,enabled)
                VALUES (?,?,?,?,?,?,?,?,?) AS incoming
                ON DUPLICATE KEY UPDATE device_name=incoming.device_name,device_code=incoming.device_code,
                    device_type=incoming.device_type,building_id=incoming.building_id,building_name=incoming.building_name,
                    upstream_node_id=incoming.upstream_node_id,enabled=incoming.enabled
                """, campus, node.id(), node.name(), node.deviceCode(), node.type(), node.buildingId(),
                node.buildingName(), node.upstreamNodeId(), node.enabled());
    }
}
