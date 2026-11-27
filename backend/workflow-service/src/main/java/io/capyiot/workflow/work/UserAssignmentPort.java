package io.capyiot.workflow.work;

import java.util.Collection;

public interface UserAssignmentPort {
  void requireRoleForDevices(
      String userId, String roleCode, Collection<DeviceQueryPort.DeviceRef> devices);
}
