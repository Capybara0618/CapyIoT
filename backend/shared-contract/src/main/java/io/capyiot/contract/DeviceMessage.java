package io.capyiot.contract;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;

public record DeviceMessage(
        @NotBlank @Size(max = 64) String eventId,
        @NotNull Type eventType,
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String campusId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String deviceId,
        @NotBlank @Size(max = 64) String sessionId,
        @NotNull @Min(0) @Max(9007199254740991L) Long seq,
        @NotNull Instant occurredAt,
        @NotNull JsonNode payload,
        @NotBlank @Size(max = 2048) String sessionToken) {
    public enum Type { HEARTBEAT, DEVICE_FAULT, DEVICE_RECOVERED, SENSOR_READING }
}
