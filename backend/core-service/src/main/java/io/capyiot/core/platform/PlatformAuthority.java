package io.capyiot.core.platform;

import io.capyiot.core.ports.DeviceQueryPort;
import io.capyiot.core.ports.UserAssignmentPort;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

/** Temporary domain-facing port; production implementation uses platform HTTP, never its database. */
public interface PlatformAuthority extends UserAssignmentPort {
    AuthorizationContext current(Jwt jwt);
    List<Map<String,Object>> assignmentCandidates(AuthorizationContext actor,List<DeviceQueryPort.DeviceRef> devices);
}
