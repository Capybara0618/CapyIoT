package io.capyiot.legacydevice;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class DeviceModels {
    private DeviceModels() {}
    public enum Type { CAMERA, NVR, ACCESS_CONTROL, SWITCH, SENSOR }
    public record CreateDevice(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,63}") String deviceCode,
            @NotNull Type type, @NotBlank @Size(max = 100) String name,
            @NotBlank String buildingId, @NotBlank String floorId,
            String areaId, String upstreamNodeId) {}
    public record UpdateDevice(
            @NotBlank @Size(max = 100) String name, @NotNull Boolean enabled,
            @NotBlank String buildingId, @NotBlank String floorId,
            String areaId, String upstreamNodeId, @NotNull @Min(0) Long expectedVersion) {}
    public record Registration(DeviceView device, String deviceSecret) {}
    public record SessionRequest(@NotBlank String deviceId,
            @NotBlank @Size(max = 256) String secret, @NotBlank @Size(max = 64) String sessionId) {}
    public record SessionResult(long sessionEpoch, String sessionToken, Instant expiresAt) {}
    public record DeviceView(String id, String deviceCode, Type type, String name, boolean enabled,
            String campusId, String buildingId, String buildingName, String floorId, String floorName,
            String areaId, String areaName, String upstreamNodeId, String upstreamNodeName,
            String connectivityStatus, String healthStatus, Instant lastHeartbeatAt,
            Instant createdAt, Instant updatedAt, long version, long stateVersion,
            long sessionEpoch, long seq) {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record Summary(long total, long online, long offline, long abnormal) {}
    public record LocationRequest(@NotBlank @Size(max = 100) String name, @NotBlank String parentId) {}
    public record LinkRequest(@NotBlank String upstreamDeviceId, @NotBlank String downstreamDeviceId,
            @NotNull @Min(0) Long expectedVersion) {}
}
