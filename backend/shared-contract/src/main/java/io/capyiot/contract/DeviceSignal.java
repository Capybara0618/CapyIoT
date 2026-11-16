package io.capyiot.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record DeviceSignal(String eventId, String eventType, int schemaVersion, String campusId,
        String deviceId, Instant occurredAt, Instant receivedAt, String traceId, String producer, State payload) {
    public record State(String connectivityStatus, String healthStatus, long stateVersion, String causationId,
            String previousConnectivityStatus, String previousHealthStatus, String reason, JsonNode evidence,
            String upstreamNodeId, boolean enabled, long topologyVersion) {}
}
