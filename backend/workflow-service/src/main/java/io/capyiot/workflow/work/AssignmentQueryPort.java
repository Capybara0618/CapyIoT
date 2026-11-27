package io.capyiot.workflow.work;

import java.util.Set;

public interface AssignmentQueryPort {
  Set<String> assignedDeviceIds(String userId, String parkId, String assignmentRelation);
}
