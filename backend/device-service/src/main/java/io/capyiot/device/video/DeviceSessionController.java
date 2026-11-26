package io.capyiot.device.video;

import io.capyiot.contract.ApiResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="capyiot.assets.enabled",havingValue="true")
class DeviceSessionController {
    @org.springframework.beans.factory.annotation.Value("${capyiot.device.session-http-test-only:false}") boolean httpTestOnly;
    private final DeviceSessions sessions;
    private final DeviceSigningKeys keys;
    private final DeviceIdentity platform;
    DeviceSessionController(DeviceSessions sessions,DeviceSigningKeys keys,DeviceIdentity platform){this.sessions=sessions;this.keys=keys;this.platform=platform;}
    @PostMapping("/api/v1/device-sessions") ApiResponse<?> create(@Valid @RequestBody DeviceSessions.SessionCommand body,jakarta.servlet.http.HttpServletRequest req){if(!req.isSecure()&&!httpTestOnly)throw new ApiProblem(426,"DEVICE_HTTPS_REQUIRED","设备认证要求 HTTPS");return ApiResponse.ok(sessions.create(body),req.getRequestId());}
    @GetMapping("/.well-known/device-jwks.json") Map<String,Object> keys(){return keys.publicKeys();}
    @PostMapping("/api/v1/devices/{id}/credential/rotate") ApiResponse<?> rotate(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody DeviceSessions.Rotate body,jakarta.servlet.http.HttpServletRequest req){return ApiResponse.ok(sessions.rotate(platform.current(jwt),key,id,body),req.getRequestId());}
}
