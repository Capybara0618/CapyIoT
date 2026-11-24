package io.capyiot.core.ports;

import java.util.Collection;

public interface UserAssignmentPort {
    void requireRoleForDevices(String userId,String roleCode,Collection<DeviceQueryPort.DeviceRef> devices);
}
