package io.capyiot.workflow.work;

import java.util.*;

public interface DeviceQueryPort {
  record DeviceRef(
      String deviceId,
      String parkId,
      String areaId,
      String assetCode,
      String deviceType,
      String name,
      String assetStatus) {}

  DeviceRef device(String id);

  Set<String> visibleDeviceIds(AuthorizationContext actor, String permission);

  List<DeviceRef> requireVisibleDevices(
      AuthorizationContext actor, String permission, Collection<String> ids);

  List<Map<String, Object>> networkRelations(String parkId, java.time.Instant asOf);
}
