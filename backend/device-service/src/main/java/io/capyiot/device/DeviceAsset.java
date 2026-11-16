package io.capyiot.device;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("device")
public class DeviceAsset {
    @TableId(type = IdType.INPUT)
    public String id;
    public String campusId;
    public String deviceCode;
    public String type;
    public String name;
    public Boolean enabled;
    public String buildingId;
    public String floorId;
    public String areaId;
    public String passwordHash;
    public String activeSessionId;
    public Long sessionEpoch;
    public Instant createdAt;
    public Instant updatedAt;
    public Long version;
}
