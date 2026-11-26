package io.capyiot.core.asset;

import jakarta.validation.constraints.*;
import java.util.*;

public final class AssetModels {
    private AssetModels() {}
    public enum DeviceType { IPC,NVR,SWITCH }
    public enum AssetStatus { REGISTERED,IN_SERVICE,OUT_OF_SERVICE,RETIRED }
    public enum AreaType { BUILDING,FLOOR,AREA }
    public record CreateArea(@Size(max=64) String parentAreaId,@NotNull AreaType areaType,
                             @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String areaCode,@NotBlank @Size(max=100) String name) {}
    public record UpdateArea(@Size(max=64) String parentAreaId,@Size(min=1,max=100) String name,
                             @Pattern(regexp="ACTIVE|INACTIVE") String status,@NotNull @Min(0) Long expectedVersion) {}
    public record CreateDevice(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String assetCode,@NotNull DeviceType deviceType,
                               @NotBlank @Size(max=100) String name,@NotBlank @Size(max=64) String areaId,
                               @Size(max=100) String manufacturer,@Size(max=100) String model,@Size(max=128) String serialNumber,
                               @Size(max=64) String managementIp,@Pattern(regexp="LOW|NORMAL|HIGH|CRITICAL") String criticality,
                               Map<@Pattern(regexp="poeInput|poeOutput") String,Boolean> capabilities) {}
    public record UpdateDevice(@Size(min=1,max=100) String name,@Size(max=64) String areaId,@Size(max=100) String manufacturer,
                               @Size(max=100) String model,@Size(max=128) String serialNumber,@Size(max=64) String managementIp,
                               @Pattern(regexp="LOW|NORMAL|HIGH|CRITICAL") String criticality,Map<String,Boolean> capabilities,
                               AssetStatus assetStatus,@NotNull @Min(0) Long expectedVersion) {}
    public record CreatePoint(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String pointCode,@NotBlank @Size(max=100) String name,
                              @NotBlank @Size(max=64) String areaId,@Size(max=1000) String description) {}
    public record UpdatePoint(@Size(min=1,max=100) String name,@Size(max=64) String areaId,@Size(max=1000) String description,
                              @Pattern(regexp="ACTIVE|INACTIVE") String status,@NotNull @Min(0) Long expectedVersion) {}
    public record BindPoint(@NotBlank String ipcDeviceId,@NotBlank @Size(max=1000) String reason,
                            @NotNull @Min(0) Long expectedPointVersion,@NotNull @Min(0) Long expectedDeviceVersion) {}
    public record EndRelation(@NotBlank @Size(max=1000) String reason,@NotNull @Min(0) Long expectedVersion) {}
    public record Network(@NotBlank String upstreamSwitchId,@NotBlank String downstreamDeviceId,@Size(max=64) String upstreamPortRef,
                          @Size(max=64) String downstreamPortRef,@NotBlank @Size(max=1000) String reason) {}
    public record Power(@NotBlank String poeSwitchId,@NotBlank @Size(max=64) String poePort,@NotBlank String ipcDeviceId,@NotBlank @Size(max=1000) String reason) {}
    public record Video(@NotBlank String nvrDeviceId,@NotBlank String ipcDeviceId,@Min(1) int channelNo,@NotBlank @Size(max=1000) String reason) {}
    public record Page<T>(List<T> items,long total,int page,int size) {}
}
