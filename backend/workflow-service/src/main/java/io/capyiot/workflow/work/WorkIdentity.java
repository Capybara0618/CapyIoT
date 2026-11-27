package io.capyiot.workflow.work;

import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;

public interface WorkIdentity {
  AuthorizationContext current(Jwt jwt);

  List<Map<String, Object>> assignmentCandidates(
      AuthorizationContext actor, List<DeviceQueryPort.DeviceRef> devices);
}
