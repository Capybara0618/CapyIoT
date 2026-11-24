package io.capyiot.core.ports;

import java.util.Set;

public interface AssignmentQueryPort {
    Set<String> assignedDeviceIds(String userId,String parkId,String assignmentRelation);
}
