package io.capyiot.contract;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;

public record DeviceEvent(
        @NotBlank @Size(max = 64) String eventId,
        @NotNull DeviceMessage.Type eventType,
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotBlank @Size(max = 64) String campusId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String sessionId,
        @NotNull @Min(1) @Max(9007199254740991L) Long sessionEpoch,
        @NotNull @Min(0) @Max(9007199254740991L) Long seq,
        @NotNull Instant occurredAt,
        @NotNull Instant receivedAt,
        @NotBlank @Size(max = 64) String traceId,
        @NotBlank String producer,
        @NotNull JsonNode payload) {
    public static final String TOPIC = "iot.device.events.v1";
    public static final String STATUS_TOPIC = "iot.device.status.v1";
    public static final String REJECTED_TOPIC = "iot.device.events.rejected.v1";
}
