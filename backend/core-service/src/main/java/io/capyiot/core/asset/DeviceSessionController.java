package io.capyiot.core.asset;

import io.capyiot.contract.ApiResponse;
import io.capyiot.core.platform.PlatformAuthority;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="capyiot.assets.enabled",havingValue="true")
class DeviceSessionController {
    private final DeviceSessions sessions;
    private final DeviceSigningKeys keys;
    private final PlatformAuthority platform;
    DeviceSessionController(DeviceSessions sessions,DeviceSigningKeys keys,PlatformAuthority platform){this.sessions=sessions;this.keys=keys;this.platform=platform;}
    @PostMapping("/api/v1/device-sessions") ApiResponse<?> create(@Valid @RequestBody DeviceSessions.SessionCommand body,jakarta.servlet.http.HttpServletRequest req){return ApiResponse.ok(sessions.create(body),req.getRequestId());}
    @GetMapping("/.well-known/device-jwks.json") Map<String,Object> keys(){return keys.publicKeys();}
    @GetMapping("/internal/devices/{id}/sessions/{sid}") DeviceSessions.SessionContext current(@PathVariable String id,@PathVariable String sid){return sessions.current(id,sid);}
    record IngestionIdentity(String clientId){}
    @PostMapping("/internal/ingestion/token") Map<String,String> ingestion(@RequestBody IngestionIdentity body){return Map.of("token",sessions.ingestionToken(body.clientId()));}
    @PostMapping("/api/v1/devices/{id}/credential/rotate") ApiResponse<?> rotate(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody DeviceSessions.Rotate body,jakarta.servlet.http.HttpServletRequest req){return ApiResponse.ok(sessions.rotate(platform.current(jwt),key,id,body),req.getRequestId());}
}
