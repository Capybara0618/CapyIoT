package io.capyiot.alarm.fault;

import org.springframework.security.oauth2.jwt.Jwt;

public interface FaultIdentity {
  AuthorizationContext current(Jwt jwt);
}
