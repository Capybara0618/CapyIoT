package io.capyiot.alarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.capyiot.contract.DeviceSignal;
import io.capyiot.contract.TopologyEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AlarmRules {
    static final String OFFLINE = "DEVICE_OFFLINE";
    static final String NETWORK = "NETWORK_UPSTREAM_FAILURE";
    static final String TEMPERATURE = "SENSOR_THRESHOLD_BREACH";
    static final String FAULT = "DEVICE_FAULT";
    private static final String GROUP = "NETWORK_INCIDENT";

    private record Active(long id, Long parent, Instant incidentAt, String relation) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TopologyProjection topology;
    private final long windowSeconds;
    private final long cooldownSeconds;
    private final int minimumDownstream;
    private final double temperatureLimit;
    private final double temperatureRecovery;

    AlarmRules(JdbcTemplate jdbc, ObjectMapper json, TopologyProjection topology,
            @Value("${capyiot.alarm-topology-window-seconds:60}") long windowSeconds,
            @Value("${capyiot.alarm-flap-cooldown-seconds:60}") long cooldownSeconds,
            @Value("${capyiot.alarm-minimum-downstream:2}") int minimumDownstream,
            @Value("${capyiot.alarm-temperature-limit:35}") double temperatureLimit,
            @Value("${capyiot.alarm-temperature-recovery:33}") double temperatureRecovery) {
        if (windowSeconds < 1 || cooldownSeconds < 1 || minimumDownstream < 2 || temperatureRecovery >= temperatureLimit) {
            throw new IllegalArgumentException("Invalid alarm rule thresholds");
        }
        this.jdbc = jdbc;
        this.json = json;
        this.topology = topology;
        this.windowSeconds = windowSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.minimumDownstream = minimumDownstream;
        this.temperatureLimit = temperatureLimit;
        this.temperatureRecovery = temperatureRecovery;
    }

    @Transactional
    public void evaluate(DeviceSignal event) {
        String campus = event.campusId();
        topology.lock(campus);
        int inserted = jdbc.update("""
                INSERT IGNORE INTO inbox_event(consumer_group,event_id) VALUES ('alarm-rule-group',?)
                """, event.eventId());
        if (inserted == 0) return;
        var state = event.payload();
        var node = topology.node(campus, event.deviceId());
        long current = jdbc.queryForList("""
                SELECT version FROM alarm_device_version WHERE campus_id=? AND device_id=?
                """, Long.class, campus, event.deviceId()).stream().findFirst().orElse(-1L);
        boolean stale = current >= state.stateVersion();
        if (node == null) throw new IllegalStateException("Signal device absent from topology projection");

        // Archive business events and recovery transitions, including stale evidence, without storing routine heartbeats.
        jdbc.update("""
                INSERT INTO raw_event(event_id,campus_id,device_id,cause_event_id,event_type,occurred_at,received_at,
                    trace_id,processing_status,upstream_node_id,topology_version,payload_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, event.eventId(), campus, event.deviceId(), state.causationId(), state.reason(),
                at(event.occurredAt()), at(event.receivedAt()), event.traceId(), stale ? "IGNORED_STALE" : "PROCESSED",
                state.upstreamNodeId(), state.topologyVersion(), serialize(state.evidence()));
        if (stale) return;
        jdbc.update("""
                INSERT INTO alarm_device_version(campus_id,device_id,version) VALUES (?,?,?) AS incoming
                ON DUPLICATE KEY UPDATE version=incoming.version
                """, campus, event.deviceId(), state.stateVersion());

        boolean changed = false;
        if ("HEARTBEAT_TIMEOUT".equals(state.reason()) && state.enabled()) {
            open(event, node, OFFLINE, "WARNING", state.upstreamNodeId());
            if (state.upstreamNodeId() != null) correlate(event, state.upstreamNodeId());
            changed = true;
        } else if ("ONLINE".equals(state.connectivityStatus())) {
            changed = recover(event, node.id(), OFFLINE);
        }

        if ("DEVICE_RECOVERED".equals(state.reason())) {
            changed = recover(event, node.id(), NETWORK) | recover(event, node.id(), FAULT) | changed;
        } else if (state.enabled() && "DEVICE_FAULT".equals(state.reason())) {
            JsonNode evidence = state.evidence();
            String fault = evidence == null ? "" : evidence.path("faultCode").asText();
            String type = switch (fault) {
                case NETWORK -> "SWITCH".equals(node.type()) ? NETWORK : FAULT;
                case TEMPERATURE -> "SENSOR".equals(node.type()) ? TEMPERATURE : FAULT;
                default -> FAULT;
            };
            open(event, node, type, type.equals(NETWORK) ? "CRITICAL" : "WARNING", null);
            if (type.equals(NETWORK)) correlate(event, node.id());
            changed = true;
        } else if (state.enabled() && "ABNORMAL".equals(state.healthStatus())
                && "NORMAL".equals(state.previousHealthStatus())
                && !"SENSOR_READING".equals(state.reason())) {
            open(event, node, FAULT, "WARNING", null);
            changed = true;
        }

        if (state.enabled() && "SENSOR".equals(node.type()) && "SENSOR_READING".equals(state.reason())
                && state.evidence() != null && state.evidence().path("temperature").isNumber()) {
            double temperature = state.evidence().path("temperature").asDouble();
            if (temperature >= temperatureLimit) {
                open(event, node, TEMPERATURE, "WARNING", null);
                changed = true;
            } else if (temperature <= temperatureRecovery) {
                changed = recover(event, node.id(), TEMPERATURE) || changed;
            }
        }
        if (changed) incrementRevision(campus);
    }

    void incrementRevision(String campus) {
        jdbc.update("UPDATE topology_checkpoint SET alarm_revision=alarm_revision+1 WHERE campus_id=?", campus);
    }

    private void open(DeviceSignal event, TopologyEvent.Node node, String type, String severity, String upstream) {
        String campus = event.campusId();
        Instant time = event.occurredAt();
        Active active = active(campus, node.id(), type);
        long id;
        String evidence = "RULE_TRIGGER";
        if (active != null) {
            id = active.id();
            evidence = "REPEAT_SUPPRESSED";
            jdbc.update("UPDATE alarm SET last_seen_at=GREATEST(last_seen_at,?),suppressed_count=suppressed_count+1,version=version+1 WHERE id=?",
                    at(time), id);
        } else {
            Long recent = type.equals(OFFLINE) ? jdbc.queryForList("""
                    SELECT id FROM alarm WHERE campus_id=? AND device_id=? AND alarm_type=?
                    AND upstream_node_id <=> ? AND parent_alarm_id IS NULL
                    AND status='RECOVERED' AND recovered_at BETWEEN ? AND ?
                    ORDER BY recovered_at DESC,id DESC LIMIT 1
                    """, Long.class, campus, node.id(), type, upstream, at(time.minusSeconds(cooldownSeconds)), at(time))
                    .stream().findFirst().orElse(null) : null;
            if (recent == null) {
                id = create(campus, node, type, severity, "INDEPENDENT", upstream, time);
            } else {
                id = recent;
                evidence = "FLAP_SUPPRESSED";
                jdbc.update("""
                        UPDATE alarm SET status='OPEN',active_marker=1,parent_alarm_id=NULL,incident_at=?,last_seen_at=?,
                          recovered_at=NULL,acknowledged_at=NULL,acknowledged_by=NULL,suppressed_count=suppressed_count+1,
                          version=version+1 WHERE id=?
                        """, at(time), at(time), id);
            }
        }
        evidence(id, event, evidence);
        link(id, node.id(), node.name(), "AFFECTED", time);
    }

    private boolean recover(DeviceSignal event, String deviceId, String type) {
        var alarm = active(event.campusId(), deviceId, type);
        if (alarm == null) return false;
        recoverRow(alarm.id(), event);
        if (alarm.parent() != null) {
            long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM alarm WHERE parent_alarm_id=? AND active_marker=1",
                    Long.class, alarm.parent());
            if (remaining == 0) recoverRow(alarm.parent(), event);
            else jdbc.update("UPDATE alarm SET version=version+1 WHERE id=?", alarm.parent());
        }
        return true;
    }

    private void recoverRow(long id, DeviceSignal event) {
        jdbc.update("""
                UPDATE alarm SET status='RECOVERED',active_marker=NULL,recovered_at=?,last_seen_at=GREATEST(last_seen_at,?),
                    version=version+1 WHERE id=?
                """, at(event.occurredAt()), at(event.occurredAt()), id);
        evidence(id, event, "RECOVERY");
    }

    private void correlate(DeviceSignal event, String upstream) {
        String campus = event.campusId();
        Instant time = event.occurredAt().truncatedTo(ChronoUnit.MILLIS);
        var root = topology.node(campus, upstream);
        if (root == null || !"SWITCH".equals(root.type())) return;
        Active explicit = active(campus, upstream, NETWORK);
        Active existing = active(campus, upstream, GROUP);
        boolean explicitNear = explicit != null && inside(explicit.incidentAt(), time)
                && (existing == null || fitsSwitchEvidence(existing.id(), explicit.incidentAt()));
        if (existing != null && !explicitNear &&
                ("CONFIRMED_UPSTREAM".equals(existing.relation()) || time.isBefore(existing.incidentAt())
                    || time.isAfter(existing.incidentAt().plusSeconds(windowSeconds)))) return;
        Instant lower = explicitNear ? explicit.incidentAt().minusSeconds(windowSeconds)
                : existing != null ? existing.incidentAt() : time.minusSeconds(windowSeconds);
        Instant upper = explicitNear ? explicit.incidentAt().plusSeconds(windowSeconds)
                : existing != null ? existing.incidentAt().plusSeconds(windowSeconds) : time.plusSeconds(windowSeconds);
        var children = jdbc.query("""
                SELECT id,device_id,device_name,incident_at FROM alarm
                WHERE campus_id=? AND alarm_type='DEVICE_OFFLINE' AND active_marker=1 AND upstream_node_id=?
                AND parent_alarm_id IS NULL AND incident_at BETWEEN ? AND ? ORDER BY incident_at,id
                """, (rs, row) -> new Child(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()),
                campus, upstream, at(lower), at(upper));
        if (!explicitNear && existing == null) children = clusteredWithinWindow(children, time);

        if (existing == null && children.size() < (explicitNear ? 1 : minimumDownstream)) return;
        if (existing != null && children.isEmpty() && !explicitNear) return;
        String relation = explicitNear || (existing != null && "CONFIRMED_UPSTREAM".equals(existing.relation()))
                ? "CONFIRMED_UPSTREAM" : "SUSPECTED_UPSTREAM";
        String severity = relation.equals("CONFIRMED_UPSTREAM") ? "CRITICAL" : "WARNING";
        Instant firstSeen = children.isEmpty() || time.isBefore(children.getFirst().incidentAt())
                ? time : children.getFirst().incidentAt();
        long id = existing == null ? create(campus, root, GROUP, severity, relation, upstream, firstSeen) : existing.id();
        if (explicitNear && explicit.parent() == null) {
            attach(id, new Child(explicit.id(), root.id(), root.name(), explicit.incidentAt()), "ROOT");
            // Copy the precise switch trigger reference onto the root for review.
            jdbc.update("""
                    INSERT IGNORE INTO alarm_evidence(alarm_id,event_id,evidence_type,created_at)
                    SELECT ?,event_id,'UPSTREAM_FAULT',created_at FROM alarm_evidence
                    WHERE alarm_id=? AND evidence_type='RULE_TRIGGER'
                    """, id, explicit.id());
        }
        for (Child child : children) attach(id, child, "AFFECTED");
        link(id, root.id(), root.name(), "ROOT", time);
        jdbc.update("""
                UPDATE alarm SET relation_kind=?,severity=?,last_seen_at=GREATEST(last_seen_at,?),version=version+1 WHERE id=?
                """, relation, severity, at(time), id);
        evidence(id, event, relation.equals("CONFIRMED_UPSTREAM") ? "TOPOLOGY_CONFIRMED" : "TOPOLOGY_CLUSTER");
    }

    private record Child(long id, String deviceId, String name, Instant incidentAt) {}

    private boolean fitsSwitchEvidence(long parent, Instant switchFaultAt) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM alarm WHERE parent_alarm_id=? AND alarm_type='DEVICE_OFFLINE'
                AND incident_at NOT BETWEEN ? AND ?
                """, Long.class, parent, at(switchFaultAt.minusSeconds(windowSeconds)),
                at(switchFaultAt.plusSeconds(windowSeconds))) == 0;
    }

    private List<Child> clusteredWithinWindow(List<Child> children, Instant anchor) {
        List<Child> best = List.of();
        int start = 0;
        for (int end = 0; end < children.size(); end++) {
            Instant last = children.get(end).incidentAt();
            while (last.isAfter(children.get(start).incidentAt().plusSeconds(windowSeconds))) start++;
            if (!last.isBefore(anchor) && !children.get(start).incidentAt().isAfter(anchor)
                    && end - start + 1 > best.size()) best = children.subList(start, end + 1);
        }
        return best;
    }

    private void attach(long parent, Child child, String impact) {
        jdbc.update("UPDATE alarm SET parent_alarm_id=?,version=version+1 WHERE id=?", parent, child.id());
        link(parent, child.deviceId(), child.name(), impact, child.incidentAt());
    }

    private boolean inside(Instant candidate, Instant anchor) {
        return !candidate.isBefore(anchor.minusSeconds(windowSeconds)) && !candidate.isAfter(anchor.plusSeconds(windowSeconds));
    }

    private Active active(String campus, String device, String type) {
        return jdbc.query("""
                SELECT id,parent_alarm_id,incident_at,relation_kind FROM alarm
                WHERE campus_id=? AND device_id=? AND alarm_type=? AND active_marker=1
                """, (rs, row) -> new Active(rs.getLong(1), rs.getObject(2, Long.class),
                rs.getTimestamp(3).toInstant(), rs.getString(4)),
                campus, device, type).stream().findFirst().orElse(null);
    }

    private long create(String campus, TopologyEvent.Node node, String type, String severity,
            String relation, String upstream, Instant time) {
        jdbc.update("""
                INSERT INTO alarm(campus_id,device_id,device_name,building_id,alarm_type,severity,status,relation_kind,
                    upstream_node_id,active_marker,first_seen_at,incident_at,last_seen_at)
                VALUES (?,?,?,?,?,?,'OPEN',?,?,1,?,?,?)
                """, campus, node.id(), node.name(), node.buildingId(), type, severity, relation, upstream,
                at(time), at(time), at(time));
        return active(campus, node.id(), type).id();
    }

    private void evidence(long id, DeviceSignal event, String kind) {
        jdbc.update("INSERT IGNORE INTO alarm_evidence(alarm_id,event_id,evidence_type,created_at) VALUES (?,?,?,?)",
                id, event.eventId(), kind, at(event.occurredAt()));
    }

    private void link(long id, String deviceId, String name, String impact, Instant firstSeen) {
        jdbc.update("""
                INSERT IGNORE INTO alarm_device_link(alarm_id,device_id,device_name,impact_kind,first_observed_at)
                VALUES (?,?,?,?,?)
                """, id, deviceId, name, impact, at(firstSeen));
    }

    private String serialize(JsonNode payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize alarm evidence", error);
        }
    }

    private static Timestamp at(Instant time) { return Timestamp.from(time.truncatedTo(ChronoUnit.MILLIS)); }
}
