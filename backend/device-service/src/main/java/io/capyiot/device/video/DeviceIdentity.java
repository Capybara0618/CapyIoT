package io.capyiot.device.video;

import org.springframework.security.oauth2.jwt.Jwt;

public interface DeviceIdentity {AuthorizationContext current(Jwt token);}
