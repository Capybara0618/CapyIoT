package io.capyiot.contract;

import java.time.Instant;
import java.util.List;

public record TopologyEvent(String eventId, String eventType, int schemaVersion, String campusId,
        Instant occurredAt, Instant receivedAt, String traceId, String producer, Change payload) {
    public static final String TOPIC = "iot.device.topology.v1";

    public record Node(String id, String name, String deviceCode, String type, String buildingId,
            String buildingName, String upstreamNodeId, boolean enabled) {}
    public record Change(long version, Node node) {}
    public record Page(long version, List<Node> nodes, String nextAfter) {}
}
