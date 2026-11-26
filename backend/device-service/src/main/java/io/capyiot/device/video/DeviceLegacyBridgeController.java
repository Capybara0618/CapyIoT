package io.capyiot.device.video;

import io.capyiot.contract.internal.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Temporary Core bridge: an expiring service key is required by DeviceSecurity, never a public fallback. */
@RestController
@io.swagger.v3.oas.annotations.Hidden
class DeviceLegacyBridgeController {
    private final DeviceInternalController queries;private final DeviceIdentity users;
    DeviceLegacyBridgeController(DeviceInternalController queries,DeviceIdentity users){this.queries=queries;this.users=users;}
    @PostMapping("/internal/device-bridge/references") DeviceApi.ScopeResult references(@RequestBody DeviceApi.Batch body){return queries.references(body);}
    @PostMapping("/internal/device-bridge/scope") DeviceApi.ScopeResult scope(@AuthenticationPrincipal Jwt jwt,@RequestBody DeviceApi.ScopeRequest body){return queries.scope(users.current(jwt),body);}
    @PostMapping("/internal/device-bridge/visible") DeviceApi.VisiblePage visible(@AuthenticationPrincipal Jwt jwt,@RequestBody DeviceApi.VisiblePageRequest body){return queries.visible(users.current(jwt),body);}
    @PostMapping("/internal/device-bridge/network") DeviceApi.NetworkPage network(@RequestBody DeviceApi.NetworkPageRequest body){return queries.network(body);}
    @PostMapping("/internal/device-bridge/evidence") FaultApi.EvidenceResult evidence(@AuthenticationPrincipal Jwt jwt,@RequestBody FaultApi.EvidenceRequest body){return queries.evidence(users.current(jwt),body);}
}
